package com.qqmu.jargus.service;

import com.qqmu.jargus.datasource.DialectDdl;
import com.qqmu.jargus.datasource.SchemaMetadata;
import com.qqmu.jargus.datasource.SqlDialectAdapter;
import com.qqmu.jargus.datasource.SqlDialectAdapter.Family;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 旧库升级迁移（幂等，可反复执行；启动时对活库跑，切换后对目标库跑）。
 * 从 AppStartupListener 抽出并做方言中立化：
 * - INFORMATION_SCHEMA 判列 → SchemaMetadata（DatabaseMetaData 快照，大小写不敏感）
 * - ADD IF NOT EXISTS（H2 专属）→ 守卫 + DialectDdl.addColumn
 * - 无 FROM 的 INSERT...SELECT WHERE NOT EXISTS（Oracle/SQLServer 不支持）→ SELECT COUNT 再 INSERT
 * - CREATE TABLE IF NOT EXISTS + AUTO_INCREMENT/BOOLEAN（H2/MySQL 专属）→ 守卫 + DialectDdl.createTable
 * - CLOB 上 TRIM（Oracle 报错）→ DialectDdl.isEmptyText
 */
@Slf4j
@Service
public class SchemaMigrations {

    /**
     * 对目标库执行全部迁移。方言决定列类型/语法渲染，metadata 快照做幂等守卫。
     */
    public void runAll(DataSource dataSource, SqlDialectAdapter dialect) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Family f = dialect.getFamily();
        SchemaMetadata meta;
        try {
            meta = SchemaMetadata.capture(dataSource);
        } catch (Exception e) {
            log.warn("读取目标库结构快照失败，跳过增量迁移: {}", e.getMessage());
            return;
        }

        // AI 厂商：max_tokens 新列（null=不限制）
        ensureColumn(jdbc, meta, f, "ai_provider_config", "max_tokens", DialectDdl.intType(f));
        // CI 触发器：代码平台地址 + 私有仓库克隆凭据
        ensureColumn(jdbc, meta, f, "ci_trigger_config", "platform_url", DialectDdl.varchar(f, 256));
        ensureColumn(jdbc, meta, f, "ci_trigger_config", "repo_username", DialectDdl.varchar(f, 128));
        ensureColumn(jdbc, meta, f, "ci_trigger_config", "repo_token", DialectDdl.varchar(f, 512));
        // 内置 LLM 模板协议收敛（UPDATE 为标准 SQL，全方言通用）
        migrateTemplateProtocols(jdbc);
        // 既有库补种新增检查器配置（architecture / dependency_vuln）
        seedNewCheckers(jdbc);
        // scan_issue 增补 AI 增强建议列
        ensureColumn(jdbc, meta, f, "scan_issue", "ai_suggestion", DialectDdl.text(f));
        ensureColumn(jdbc, meta, f, "scan_issue", "ai_suggestion_at", DialectDdl.tsType(f));
        ensureColumn(jdbc, meta, f, "scan_issue", "line_points", DialectDdl.text(f));
        ensureColumn(jdbc, meta, f, "scan_issue", "occurrence_count", DialectDdl.intType(f) + " DEFAULT 1");
        // scan_task 增补五级计数列（必须在任何计数回写之前完成）
        ensureColumn(jdbc, meta, f, "scan_task", "blocker_count", DialectDdl.intType(f) + " DEFAULT 0");
        ensureColumn(jdbc, meta, f, "scan_task", "critical_count", DialectDdl.intType(f) + " DEFAULT 0");
        ensureColumn(jdbc, meta, f, "scan_task", "major_count", DialectDdl.intType(f) + " DEFAULT 0");
        ensureColumn(jdbc, meta, f, "scan_task", "minor_count", DialectDdl.intType(f) + " DEFAULT 0");
        // 邮件通知字段
        ensureColumn(jdbc, meta, f, "scan_task", "notify_enabled", DialectDdl.boolDefault(f, false));
        ensureColumn(jdbc, meta, f, "scan_task", "notify_recipient_ids", DialectDdl.varchar(f, 512));
        ensureColumn(jdbc, meta, f, "scan_task", "mail_status", DialectDdl.varchar(f, 16));
        ensureColumn(jdbc, meta, f, "ci_trigger_config", "notify_enabled", DialectDdl.boolDefault(f, false));
        ensureColumn(jdbc, meta, f, "ci_trigger_config", "notify_recipient_ids", DialectDdl.varchar(f, 512));
        // 旧库补建表（全新库 initializeSchema 已建，守卫直接跳过）
        ensureUserTables(jdbc, meta, f);
        ensureGateSettingTable(jdbc, meta, f);
        ensureMailTables(jdbc, meta, f);
        ensureArtifactDismissalTable(jdbc, meta, f);
        ensureSsoConfigTable(jdbc, meta, f);
        // 历史扫描问题的修复建议回填
        backfillSuggestions(jdbc, f);
    }

    /** 内置 LLM 模板统一收敛到 OPENAI_COMPATIBLE / ANTHROPIC 两种协议（幂等） */
    private void migrateTemplateProtocols(JdbcTemplate jdbc) {
        try {
            jdbc.update("UPDATE llm_template SET protocol_type='OPENAI_COMPATIBLE', base_url=?, " +
                    "auth_type='BEARER', auth_header_name='Authorization', default_model='ernie-4.0-turbo-8k' " +
                    "WHERE template_name='百度千帆'", "https://qianfan.baidubce.com/v2");
            jdbc.update("UPDATE llm_template SET protocol_type='OPENAI_COMPATIBLE', base_url=?, " +
                    "auth_type='BEARER', auth_header_name='Authorization', default_model='gemini-2.0-flash' " +
                    "WHERE template_name='Gemini'", "https://generativelanguage.googleapis.com/v1beta/openai");
            jdbc.update("UPDATE llm_template SET protocol_type='ANTHROPIC', base_url=?, " +
                    "auth_type='API_KEY_HEADER', auth_header_name='x-api-key', default_model='claude-3-5-sonnet-latest' " +
                    "WHERE template_name='Claude'", "https://api.anthropic.com/v1");
            jdbc.update("UPDATE llm_template SET protocol_type='OPENAI_COMPATIBLE', " +
                    "auth_type='BEARER', auth_header_name='Authorization' WHERE template_name IN ('Ollama','vLLM','LocalAI')");
        } catch (Exception e) {
            log.warn("内置 LLM 模板升级失败: {}", e.getMessage());
        }
    }

    /**
     * 为既有数据库补种新增的内置检查器配置（幂等）：
     * SELECT COUNT 再 INSERT（Oracle/SQL Server 不支持无 FROM 的 INSERT...SELECT）
     */
    private void seedNewCheckers(JdbcTemplate jdbc) {
        Object[][] seeds = {
                {"architecture", "架构约束检查", "架构",
                        "检查分层架构约束：控制器不得跨层访问DAO、下层不得反向依赖上层、实体不得泄漏到接口层",
                        true, true, 18},
                {"dependency_vuln", "依赖漏洞扫描", "依赖",
                        "解析pom.xml/build.gradle依赖，与内置漏洞库匹配已知CVE（可选OSV在线增强）",
                        true, true, 19}
        };
        String countSql = "SELECT COUNT(*) FROM checker_config WHERE checker_code = ?";
        String insertSql = "INSERT INTO checker_config " +
                "(checker_code, checker_name, checker_category, description, is_enabled, is_local, sort_order) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        for (Object[] s : seeds) {
            try {
                Integer cnt = jdbc.queryForObject(countSql, Integer.class, s[0]);
                if (cnt != null && cnt > 0) {
                    continue;
                }
                jdbc.update(insertSql, s[0], s[1], s[2], s[3], s[4], s[5], s[6]);
                log.info("补种检查器配置: {}", s[0]);
            } catch (Exception e) {
                log.warn("补种检查器配置失败 {}: {}", s[0], e.getMessage());
            }
        }
    }

    /** 用户/远程认证/操作日志三表（旧库升级） */
    private void ensureUserTables(JdbcTemplate jdbc, SchemaMetadata meta, Family f) {
        createIfAbsent(jdbc, meta, f, "sys_user", List.of(
                DialectDdl.identityPk(f),
                "username " + DialectDdl.varchar(f, 64) + " NOT NULL UNIQUE",
                "password_hash " + DialectDdl.varchar(f, 256) + " NOT NULL",
                "nickname " + DialectDdl.varchar(f, 128),
                "role " + DialectDdl.varchar(f, 32) + " DEFAULT 'VIEWER' NOT NULL",
                "source " + DialectDdl.varchar(f, 32) + " DEFAULT 'LOCAL' NOT NULL",
                "is_enabled " + DialectDdl.boolDefault(f, true),
                "is_password_default " + DialectDdl.boolDefault(f, true),
                DialectDdl.timestampCol("last_login_at", f),
                DialectDdl.timestampNow("created_at", f),
                DialectDdl.timestampNow("updated_at", f)
        ));
        createIfAbsent(jdbc, meta, f, "remote_auth_config", List.of(
                DialectDdl.identityPk(f),
                "config_name " + DialectDdl.varchar(f, 128) + " NOT NULL",
                "auth_type " + DialectDdl.varchar(f, 32) + " DEFAULT 'OAUTH2' NOT NULL",
                "login_url " + DialectDdl.varchar(f, 512),
                "user_info_url " + DialectDdl.varchar(f, 512),
                "token_url " + DialectDdl.varchar(f, 512),
                "client_id " + DialectDdl.varchar(f, 256),
                "client_secret " + DialectDdl.varchar(f, 512),
                "username_field " + DialectDdl.varchar(f, 64),
                "nickname_field " + DialectDdl.varchar(f, 128),
                "role_field " + DialectDdl.varchar(f, 64),
                "role_mapping " + DialectDdl.text(f),
                "is_enabled " + DialectDdl.boolDefault(f, false),
                DialectDdl.timestampNow("created_at", f),
                DialectDdl.timestampNow("updated_at", f)
        ));
        createIfAbsent(jdbc, meta, f, "sys_oper_log", List.of(
                DialectDdl.identityPk(f),
                "username " + DialectDdl.varchar(f, 64),
                "operation " + DialectDdl.varchar(f, 128),
                "method " + DialectDdl.varchar(f, 16),
                "params " + DialectDdl.text(f),
                "ip " + DialectDdl.varchar(f, 64),
                "status " + DialectDdl.varchar(f, 16),
                "error_msg " + DialectDdl.varchar(f, 512),
                DialectDdl.timestampNow("created_at", f)
        ));
    }

    /** 门禁自定义配置表（单行 id=1；无行 = 用 application.yml 默认值） */
    private void ensureGateSettingTable(JdbcTemplate jdbc, SchemaMetadata meta, Family f) {
        List<String> cols = new ArrayList<>();
        cols.add(DialectDdl.plainBigintPk(f));
        for (String c : List.of("blocker_weight", "critical_weight", "major_weight",
                "minor_weight", "info_weight", "pass_score", "blocker_limit",
                "excellent_score", "good_score", "fair_score")) {
            cols.add(c + " " + DialectDdl.intType(f) + " NOT NULL");
        }
        cols.add(DialectDdl.timestampNow("updated_at", f));
        createIfAbsent(jdbc, meta, f, "gate_setting", cols);
    }

    /** 邮件管理两表（发件配置、通知收件人） */
    private void ensureMailTables(JdbcTemplate jdbc, SchemaMetadata meta, Family f) {
        createIfAbsent(jdbc, meta, f, "mail_sender", List.of(
                DialectDdl.identityPk(f),
                "name " + DialectDdl.varchar(f, 128) + " NOT NULL",
                "host " + DialectDdl.varchar(f, 256) + " NOT NULL",
                "port " + DialectDdl.intType(f) + " DEFAULT 465 NOT NULL",
                "password " + DialectDdl.varchar(f, 512),
                "from_address " + DialectDdl.varchar(f, 256) + " NOT NULL",
                "from_alias " + DialectDdl.varchar(f, 128),
                "use_starttls " + DialectDdl.boolDefault(f, false),
                "use_ssl " + DialectDdl.boolDefault(f, true),
                "is_enabled " + DialectDdl.boolDefault(f, false),
                DialectDdl.timestampNow("created_at", f),
                DialectDdl.timestampNow("updated_at", f)
        ));
        createIfAbsent(jdbc, meta, f, "mail_recipient", List.of(
                DialectDdl.identityPk(f),
                "name " + DialectDdl.varchar(f, 128) + " NOT NULL",
                "email " + DialectDdl.varchar(f, 256) + " NOT NULL UNIQUE",
                DialectDdl.timestampNow("created_at", f),
                DialectDdl.timestampNow("updated_at", f)
        ));
    }

    /** 同版本号安装包重发提示的忽略记录（固定单行 id=1；无行 = 未忽略） */
    private void ensureArtifactDismissalTable(JdbcTemplate jdbc, SchemaMetadata meta, Family f) {
        createIfAbsent(jdbc, meta, f, "update_artifact_dismissal", List.of(
                DialectDdl.plainBigintPk(f),
                "artifact_key " + DialectDdl.varchar(f, 160) + " NOT NULL",
                "dismissed_at " + DialectDdl.tsType(f) + " NOT NULL"
        ));
    }

    /** 企业统一认证配置（固定单行 id=1；无行 = 未配置） */
    private void ensureSsoConfigTable(JdbcTemplate jdbc, SchemaMetadata meta, Family f) {
        createIfAbsent(jdbc, meta, f, "sso_config", List.of(
                DialectDdl.plainBigintPk(f),
                "is_enabled " + DialectDdl.boolDefault(f, false),
                "auth_mode " + DialectDdl.varchar(f, 16) + " DEFAULT 'TICKET' NOT NULL",
                "admin_users " + DialectDdl.varchar(f, 1024),
                "login_entry_url " + DialectDdl.varchar(f, 512),
                "logout_redirect_url " + DialectDdl.varchar(f, 512),
                "local_login_enabled " + DialectDdl.boolDefault(f, true),
                "user_header " + DialectDdl.varchar(f, 128),
                "admin_header " + DialectDdl.varchar(f, 128),
                "admin_value " + DialectDdl.varchar(f, 128),
                "secret_header_name " + DialectDdl.varchar(f, 128),
                "secret_header_value " + DialectDdl.varchar(f, 512),
                "validate_url " + DialectDdl.varchar(f, 512),
                "validate_method " + DialectDdl.varchar(f, 8) + " DEFAULT 'GET'",
                "ticket_param " + DialectDdl.varchar(f, 64) + " DEFAULT 'ticket'",
                "success_field " + DialectDdl.varchar(f, 128),
                "success_value " + DialectDdl.varchar(f, 128),
                "user_field " + DialectDdl.varchar(f, 128),
                "admin_field " + DialectDdl.varchar(f, 128),
                "timeout_ms " + DialectDdl.intType(f) + " DEFAULT 5000",
                "allow_insecure_http " + DialectDdl.boolDefault(f, false),
                DialectDdl.timestampNow("updated_at", f)
        ));
    }

    /**
     * 历史问题数据回填修复建议（幂等）：
     * 早期检查器不写 suggestion，老任务的问题看不到修复建议，
     * 按规则码把仍为空的行补上目录默认值，只动空值不覆盖检查器自带建议。
     */
    private void backfillSuggestions(JdbcTemplate jdbc, Family f) {
        String sql = "UPDATE scan_issue SET suggestion = ? WHERE rule_code = ? AND "
                + DialectDdl.isEmptyText("suggestion", f);
        int total = 0;
        for (Map.Entry<String, String> e : SuggestionCatalog.all().entrySet()) {
            try {
                total += jdbc.update(sql, e.getValue(), e.getKey());
            } catch (Exception ex) {
                log.warn("回填修复建议失败 {}: {}", e.getKey(), ex.getMessage());
            }
        }
        if (total > 0) {
            log.info("历史问题修复建议回填: {} 行", total);
        }
    }

    /** 列缺失则 ALTER ADD（存在性判断来自结构快照，不再依赖 INFORMATION_SCHEMA） */
    private void ensureColumn(JdbcTemplate jdbc, SchemaMetadata meta, Family f,
                              String table, String column, String typeSql) {
        if (meta.columnExists(table, column)) {
            return;
        }
        try {
            jdbc.execute(DialectDdl.addColumn(table, column, typeSql, f));
            log.info("新增列: {}.{}", table, column);
        } catch (Exception e) {
            log.warn("检查/新增列失败 {}.{}: {}", table, column, e.getMessage());
        }
    }

    /** 表缺失则按方言渲染建表（存在性判断来自结构快照，不用 IF NOT EXISTS） */
    private void createIfAbsent(JdbcTemplate jdbc, SchemaMetadata meta, Family f,
                                String table, List<String> columnDefs) {
        if (meta.tableExists(table)) {
            return;
        }
        try {
            jdbc.execute(DialectDdl.createTable(table, columnDefs, f));
            log.info("补建表: {}", table);
        } catch (Exception e) {
            log.warn("补建表失败 {}: {}", table, e.getMessage());
        }
    }
}

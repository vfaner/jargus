package com.qqmu.jargus.config;

import com.qqmu.jargus.datasource.ActiveDialectHolder;
import com.qqmu.jargus.datasource.SqlDialectAdapter;
import com.qqmu.jargus.service.AuthService;
import com.qqmu.jargus.service.IgnoreRuleService;
import com.qqmu.jargus.service.IssueMergeService;
import com.qqmu.jargus.service.ReportService;
import com.qqmu.jargus.service.SchemaInitService;
import com.qqmu.jargus.service.SchemaMigrations;
import com.qqmu.jargus.service.SeverityMigrationService;
import com.qqmu.jargus.service.SsoConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 应用启动监听器
 * 负责在启动时初始化活库的表结构、增量迁移和默认用户。
 * 方言相关的增量迁移已抽到 SchemaMigrations（启动与切换后共用）；
 * 活库方言以 ActiveDialectHolder 为准（DataSourceConfig 启动恢复时已设置，默认 H2）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppStartupListener {

    private final DataSource dataSource;
    private final SchemaInitService schemaInitService;
    private final SchemaMigrations schemaMigrations;
    private final AuthService authService;
    private final IgnoreRuleService ignoreRuleService;
    private final IssueMergeService issueMergeService;
    private final SeverityMigrationService severityMigrationService;
    private final ReportService reportService;
    private final SsoConfigService ssoConfigService;

    @Value("${app.sso.reset-local-login:false}")
    private boolean resetLocalLogin;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("应用启动完成，开始检查数据库初始化状态...");

        try {
            SqlDialectAdapter dialect = ActiveDialectHolder.get();

            // 活库中是否已有表结构（按活库方言探测；启动恢复失败时 holder 仍为 H2）
            boolean schemaExists = schemaInitService.checkSchemaExists(dataSource, dialect);

            if (!schemaExists) {
                log.info("检测到数据库为空，开始初始化表结构...");
                boolean success = schemaInitService.initializeSchema(dataSource, dialect);
                if (success) {
                    log.info("数据库表结构初始化成功");
                } else {
                    log.error("数据库表结构初始化失败");
                }
            } else {
                String version = schemaInitService.getSchemaVersion(dataSource, dialect);
                log.info("数据库表结构已存在，版本: {}", version);
            }

            // 增量迁移（幂等；对活库执行，方言中立）
            schemaMigrations.runAll(dataSource, dialect);

            // 统一认证安全网：配置错误导致本地登录关闭且 SSO 不可用时自动放开
            ssoConfigService.applyStartupSafetyNets(resetLocalLogin);

            // 表结构就绪后，刷新依赖数据库的缓存（@PostConstruct 阶段表可能还不存在）
            ignoreRuleService.refreshCache();

            // 历史问题合并升级：同文件同规则多点合并为一条，清理 import 误报的重复代码
            issueMergeService.backfill();
            // 三级 BUG/WARNING/INFO → 五级 BLOCKER/CRITICAL/MAJOR/MINOR/INFO 迁移
            severityMigrationService.backfill();
            // 报告磁盘缓存对账：旧版本缓存、AI 深度评审中途预览产生的半成品缓存一律清理
            reportService.purgeStaleReportCaches();
            // 初始化默认用户
            authService.initDefaultUsers();

        } catch (Exception e) {
            log.error("初始化失败: {}", e.getMessage(), e);
        }

        log.info("========================================");
        log.info("  百目 JArgus 启动成功！");
        log.info("  访问地址: http://localhost:8080");
        log.info("========================================");
    }
}

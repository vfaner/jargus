-- ============================================================
-- 百目 JArgus - PostgreSQL Database Schema
-- 适用: PostgreSQL / openGauss / 人大金仓 KingBase / 瀚高 HighGo / 海量 Vastbase
-- Version: 1.0.0
-- 说明: 自增列用 BIGSERIAL（兼容 openGauss/Vastbase 等 PG9.2 血统内核，PG10+ 的
--       IDENTITY 语法不通用）；同样允许显式 id 写入，便于切换时整表复制；不含 database_config 种子行（由切换流程写入）
-- ============================================================

-- ============================================================
-- 表 1: schema_version (表结构版本)
-- ============================================================
CREATE TABLE IF NOT EXISTS schema_version (
    id BIGSERIAL PRIMARY KEY,
    version VARCHAR(32) NOT NULL,
    description VARCHAR(256),
    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 2: database_config (数据库配置)
-- ============================================================
CREATE TABLE IF NOT EXISTS database_config (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    db_type VARCHAR(32) NOT NULL,
    driver_class VARCHAR(256),
    jdbc_url VARCHAR(512),
    username VARCHAR(128),
    password VARCHAR(512),
    max_pool_size INTEGER DEFAULT 10,
    min_idle INTEGER DEFAULT 5,
    connection_timeout INTEGER DEFAULT 30000,
    dialect VARCHAR(32),
    driver_jar_path VARCHAR(512),
    connection_properties TEXT,
    is_custom BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT FALSE,
    schema_version VARCHAR(32),
    is_initialized BOOLEAN DEFAULT FALSE,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 3: ai_provider_config (AI厂商配置)
-- ============================================================
CREATE TABLE IF NOT EXISTS ai_provider_config (
    id BIGSERIAL PRIMARY KEY,
    provider_name VARCHAR(64) NOT NULL,
    display_name VARCHAR(128),
    protocol_type VARCHAR(32) NOT NULL,
    base_url VARCHAR(512),
    api_key VARCHAR(512),
    secret_key VARCHAR(512),
    default_model VARCHAR(128),
    available_models TEXT,
    auth_type VARCHAR(32) DEFAULT 'BEARER',
    auth_header_name VARCHAR(128) DEFAULT 'Authorization',
    request_method VARCHAR(16) DEFAULT 'POST',
    request_headers TEXT,
    request_body_template TEXT,
    response_content_path VARCHAR(256),
    response_error_path VARCHAR(256),
    timeout_seconds INTEGER DEFAULT 60,
    max_tokens INTEGER,
    is_custom BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT FALSE,
    is_enabled BOOLEAN DEFAULT TRUE,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 4: llm_template (LLM配置模板)
-- ============================================================
CREATE TABLE IF NOT EXISTS llm_template (
    id BIGSERIAL PRIMARY KEY,
    template_name VARCHAR(128) NOT NULL,
    provider_name VARCHAR(64),
    protocol_type VARCHAR(32) NOT NULL,
    base_url VARCHAR(512),
    auth_type VARCHAR(32) DEFAULT 'BEARER',
    auth_header_name VARCHAR(128) DEFAULT 'Authorization',
    request_body_template TEXT,
    response_content_path VARCHAR(256),
    response_error_path VARCHAR(256),
    default_model VARCHAR(128),
    description VARCHAR(512),
    is_builtin BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 5: scan_task (扫描任务)
-- ============================================================
CREATE TABLE IF NOT EXISTS scan_task (
    id BIGSERIAL PRIMARY KEY,
    task_name VARCHAR(256) NOT NULL,
    project_name VARCHAR(256),
    source_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) DEFAULT 'PENDING',
    total_files INTEGER DEFAULT 0,
    total_lines INTEGER DEFAULT 0,
    blocker_count INTEGER DEFAULT 0,
    critical_count INTEGER DEFAULT 0,
    major_count INTEGER DEFAULT 0,
    minor_count INTEGER DEFAULT 0,
    info_count INTEGER DEFAULT 0,
    total_issues INTEGER DEFAULT 0,
    jdk_version VARCHAR(32),
    spring_boot_version VARCHAR(32),
    skip_unit_test BOOLEAN DEFAULT FALSE,
    include_test_code BOOLEAN DEFAULT FALSE,
    enable_ai_review BOOLEAN DEFAULT TRUE,
    ai_issue_count INTEGER DEFAULT 0,
    notify_enabled BOOLEAN DEFAULT FALSE,
    notify_recipient_ids VARCHAR(512),
    mail_status VARCHAR(16),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    duration_seconds BIGINT DEFAULT 0,
    error_message TEXT,
    snapshot_path VARCHAR(512),
    report_path VARCHAR(512),
    created_by VARCHAR(128),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 6: scan_issue (扫描问题详情)
-- ============================================================
CREATE TABLE IF NOT EXISTS scan_issue (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    file_path VARCHAR(512) NOT NULL,
    file_name VARCHAR(256),
    line_start INTEGER,
    line_end INTEGER,
    column_start INTEGER,
    column_end INTEGER,
    line_points TEXT,
    occurrence_count INTEGER DEFAULT 1,
    issue_level VARCHAR(16) NOT NULL, -- BLOCKER/CRITICAL/MAJOR/MINOR/INFO
    checker_type VARCHAR(64) NOT NULL,
    checker_name VARCHAR(128),
    rule_code VARCHAR(64),
    title VARCHAR(256) NOT NULL,
    description TEXT,
    code_snippet TEXT,
    suggestion TEXT,
    severity INTEGER DEFAULT 1, -- 严重度秩 1-5（INFO=1..BLOCKER=5）
    is_ai_generated BOOLEAN DEFAULT FALSE,
    is_ignored BOOLEAN DEFAULT FALSE,
    ignore_type VARCHAR(32),
    ignore_reason VARCHAR(512),
    ai_explanation TEXT,
    ai_suggestion TEXT,
    ai_suggestion_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_scan_issue_task_id ON scan_issue (task_id);
CREATE INDEX IF NOT EXISTS idx_scan_issue_file_path ON scan_issue (file_path);
CREATE INDEX IF NOT EXISTS idx_scan_issue_issue_level ON scan_issue (issue_level);

-- ============================================================
-- 表 7: ignore_rule (强制忽略规则)
-- ============================================================
CREATE TABLE IF NOT EXISTS ignore_rule (
    id BIGSERIAL PRIMARY KEY,
    rule_type VARCHAR(32) NOT NULL,
    rule_code VARCHAR(64),
    file_pattern VARCHAR(512),
    file_path VARCHAR(512),
    line_number INTEGER,
    reason VARCHAR(512),
    is_enabled BOOLEAN DEFAULT TRUE,
    created_by VARCHAR(128),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 8: review_rule (评审规则配置)
-- ============================================================
CREATE TABLE IF NOT EXISTS review_rule (
    id BIGSERIAL PRIMARY KEY,
    rule_code VARCHAR(64) NOT NULL UNIQUE,
    rule_name VARCHAR(128) NOT NULL,
    rule_category VARCHAR(64),
    description TEXT,
    default_level VARCHAR(16) DEFAULT 'MAJOR',
    is_enabled BOOLEAN DEFAULT TRUE,
    is_builtin BOOLEAN DEFAULT TRUE,
    params TEXT,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 9: checker_config (检查器配置)
-- ============================================================
CREATE TABLE IF NOT EXISTS checker_config (
    id BIGSERIAL PRIMARY KEY,
    checker_code VARCHAR(64) NOT NULL UNIQUE,
    checker_name VARCHAR(128) NOT NULL,
    checker_category VARCHAR(64),
    description TEXT,
    is_enabled BOOLEAN DEFAULT TRUE,
    is_local BOOLEAN DEFAULT TRUE,
    params TEXT,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 10: ci_trigger_config (CI触发配置)
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_trigger_config (
    id BIGSERIAL PRIMARY KEY,
    config_name VARCHAR(128) NOT NULL,
    platform VARCHAR(32),
    platform_url VARCHAR(256),
    repo_scope VARCHAR(256),
    webhook_url VARCHAR(512),
    secret_token VARCHAR(256),
    repo_username VARCHAR(128),
    repo_token VARCHAR(512),
    branch_filter VARCHAR(256),
    skip_unit_test BOOLEAN DEFAULT FALSE,
    include_test_code BOOLEAN DEFAULT FALSE,
    enable_ai_review BOOLEAN DEFAULT TRUE,
    auto_comment BOOLEAN DEFAULT FALSE,
    notify_enabled BOOLEAN DEFAULT FALSE,
    notify_recipient_ids VARCHAR(512),
    is_enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 11: ci_scan_record (CI扫描记录)
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_scan_record (
    id BIGSERIAL PRIMARY KEY,
    trigger_config_id BIGINT,
    task_id BIGINT,
    platform VARCHAR(32),
    project_url VARCHAR(512),
    commit_id VARCHAR(128),
    branch VARCHAR(128),
    mr_pr_id VARCHAR(64),
    mr_pr_title VARCHAR(256),
    author VARCHAR(128),
    status VARCHAR(32) DEFAULT 'PENDING',
    result_url VARCHAR(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 12: ci_token (CI令牌)
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_token (
    id BIGSERIAL PRIMARY KEY,
    token_name VARCHAR(128) NOT NULL,
    token_value VARCHAR(256) NOT NULL UNIQUE,
    description VARCHAR(512),
    is_enabled BOOLEAN DEFAULT TRUE,
    expires_at TIMESTAMP,
    last_used_at TIMESTAMP,
    created_by VARCHAR(128),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 13: sys_user (系统用户)
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(256) NOT NULL,
    nickname VARCHAR(128),
    role VARCHAR(32) NOT NULL DEFAULT 'VIEWER',
    source VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
    is_enabled BOOLEAN DEFAULT TRUE,
    is_password_default BOOLEAN DEFAULT TRUE,
    last_login_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 默认用户由应用首次启动时创建（BCrypt 加密）：
--   admin / 123456  （管理员，ADMIN）
--   view  / 123456  （只读用户，VIEWER）
-- 见 AuthService.initDefaultUsers()

-- ============================================================
-- 表 14: remote_auth_config (远端登录配置)
-- ============================================================
CREATE TABLE IF NOT EXISTS remote_auth_config (
    id BIGSERIAL PRIMARY KEY,
    config_name VARCHAR(128) NOT NULL,
    auth_type VARCHAR(32) NOT NULL DEFAULT 'OAUTH2',
    login_url VARCHAR(512),
    user_info_url VARCHAR(512),
    token_url VARCHAR(512),
    client_id VARCHAR(256),
    client_secret VARCHAR(512),
    username_field VARCHAR(64),
    nickname_field VARCHAR(128),
    role_field VARCHAR(64),
    role_mapping TEXT,
    is_enabled BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 15: sys_oper_log (操作日志)
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_oper_log (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64),
    operation VARCHAR(128),
    method VARCHAR(16),
    params TEXT,
    ip VARCHAR(64),
    status VARCHAR(16),
    error_msg VARCHAR(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 16: gate_setting (质量门禁自定义配置，单行 id=1；无行=用 application.yml 默认值)
-- ============================================================
CREATE TABLE IF NOT EXISTS gate_setting (
    id BIGINT PRIMARY KEY,
    blocker_weight INTEGER NOT NULL,
    critical_weight INTEGER NOT NULL,
    major_weight INTEGER NOT NULL,
    minor_weight INTEGER NOT NULL,
    info_weight INTEGER NOT NULL,
    pass_score INTEGER NOT NULL,
    blocker_limit INTEGER NOT NULL,
    excellent_score INTEGER NOT NULL,
    good_score INTEGER NOT NULL,
    fair_score INTEGER NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 17: mail_sender (发件配置；同一时间仅允许一条 is_enabled=true，由服务层保证)
-- ============================================================
CREATE TABLE IF NOT EXISTS mail_sender (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    host VARCHAR(256) NOT NULL,
    port INTEGER NOT NULL DEFAULT 465,
    password VARCHAR(512),
    from_address VARCHAR(256) NOT NULL,
    from_alias VARCHAR(128),
    use_starttls BOOLEAN DEFAULT FALSE,
    use_ssl BOOLEAN DEFAULT TRUE,
    is_enabled BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 18: mail_recipient (扫描通知邮件收件人)
-- ============================================================
CREATE TABLE IF NOT EXISTS mail_recipient (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    email VARCHAR(256) NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 初始化数据
-- ============================================================

-- 插入 schema 版本
INSERT INTO schema_version (version, description) VALUES ('1.0.0', '初始版本');

-- 注意：database_config 种子行由切换流程写入，脚本不预置（避免出现双 active）

-- 插入内置 LLM 模板
INSERT INTO llm_template (template_name, provider_name, protocol_type, base_url, auth_type, auth_header_name, request_body_template, response_content_path, response_error_path, default_model, description, is_builtin) VALUES
('OpenAI 兼容', 'openai', 'OPENAI_COMPATIBLE', 'https://api.openai.com/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7,"max_tokens":4096}',
 'choices[0].message.content', 'error.message', 'gpt-4', 'OpenAI 兼容协议通用模板', TRUE),
('阿里百炼', 'qwen', 'OPENAI_COMPATIBLE', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'message', 'qwen-plus', '阿里百炼通义千问', TRUE),
('火山方舟', 'doubao', 'OPENAI_COMPATIBLE', 'https://ark.cn-beijing.volces.com/api/v3', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'doubao-pro', '火山引擎方舟', TRUE),
('DeepSeek', 'deepseek', 'OPENAI_COMPATIBLE', 'https://api.deepseek.com/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'deepseek-chat', 'DeepSeek', TRUE),
('Kimi', 'kimi', 'OPENAI_COMPATIBLE', 'https://api.moonshot.cn/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'moonshot-v1-8k', 'Kimi 月之暗面', TRUE),
('智谱 GLM', 'zhipu', 'OPENAI_COMPATIBLE', 'https://open.bigmodel.cn/api/paas/v4', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'glm-4', '智谱清言', TRUE),
('百度千帆', 'qianfan', 'OPENAI_COMPATIBLE', 'https://qianfan.baidubce.com/v2', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'ernie-4.0-turbo-8k', '百度千帆（OpenAI 兼容端点）', TRUE),
('Gemini', 'gemini', 'OPENAI_COMPATIBLE', 'https://generativelanguage.googleapis.com/v1beta/openai', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'gemini-2.0-flash', 'Google Gemini（OpenAI 兼容端点）', TRUE),
('Claude', 'claude', 'ANTHROPIC', 'https://api.anthropic.com/v1', 'API_KEY_HEADER', 'x-api-key',
 NULL,
 'content[0].text', 'error.message', 'claude-3-5-sonnet-latest', 'Anthropic Claude（x-api-key 鉴权）', TRUE),
('Ollama', 'ollama', 'OPENAI_COMPATIBLE', 'http://localhost:11434/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'llama3', 'Ollama 本地部署（OpenAI 兼容）', TRUE),
('vLLM', 'vllm', 'OPENAI_COMPATIBLE', 'http://localhost:8000/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'default', 'vLLM 私有部署（OpenAI 兼容）', TRUE),
('LocalAI', 'localai', 'OPENAI_COMPATIBLE', 'http://localhost:8080/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'gpt4all-j', 'LocalAI 本地部署（OpenAI 兼容）', TRUE);

-- 插入默认检查器配置
INSERT INTO checker_config (checker_code, checker_name, checker_category, description, is_enabled, is_local, sort_order) VALUES
('compilation', '编译诊断', '基础', '使用 javax.tools 进行编译，获取编译错误和警告', TRUE, TRUE, 1),
('nullpointer', '空指针检测', '缺陷', '基于AST分析潜在的空指针风险', TRUE, TRUE, 2),
('unused_method', '未使用方法检测', '冗余', '基于调用图分析未被调用的方法', TRUE, TRUE, 3),
('deprecated_method', '废弃方法检测', '兼容性', '检测使用了 @Deprecated 注解的方法', TRUE, TRUE, 4),
('complexity', '圈复杂度检测', '质量', '检测圈复杂度超标的方法', TRUE, TRUE, 5),
('naming', '命名规范检查', '风格', '检查类、方法、变量命名是否符合规范', TRUE, TRUE, 6),
('code_style', '代码风格检查', '风格', '检查代码风格问题，如魔法数字、过长方法等', TRUE, TRUE, 7),
('duplicate_code', '重复代码检测', '冗余', '检测重复的代码块', TRUE, TRUE, 8),
('exception_handling', '异常处理检查', '缺陷', '检查异常处理不当的问题', TRUE, TRUE, 9),
('resource_leak', '资源泄露检测', '缺陷', '检测未正确关闭的资源', TRUE, TRUE, 10),
('security', '安全漏洞检测', '安全', '检测常见的安全漏洞', TRUE, TRUE, 11),
('concurrency', '并发问题检测', '并发', '检测多线程并发问题', TRUE, TRUE, 12),
('performance', '性能问题检测', '性能', '检测常见性能问题', TRUE, TRUE, 13),
('spring_best_practice', 'Spring最佳实践', '框架', '检查Spring使用的最佳实践', TRUE, TRUE, 14),
('ai_semantic', 'AI语义评审', 'AI', '使用AI进行语义级代码评审', TRUE, FALSE, 15),
('ai_security', 'AI安全评审', 'AI', '使用AI进行安全漏洞深度分析', TRUE, FALSE, 16),
('ai_design', 'AI设计评审', 'AI', '使用AI进行代码设计评审', TRUE, FALSE, 17),
('architecture', '架构约束检查', '架构', '检查分层架构约束：控制器不得跨层访问DAO、下层不得反向依赖上层、实体不得泄漏到接口层', TRUE, TRUE, 18),
('dependency_vuln', '依赖漏洞扫描', '依赖', '解析pom.xml/build.gradle依赖，与内置漏洞库匹配已知CVE（可选OSV在线增强）', TRUE, TRUE, 19);

-- 插入默认评审规则
INSERT INTO review_rule (rule_code, rule_name, rule_category, description, default_level, is_enabled, is_builtin, sort_order) VALUES
('MAX_COMPLEXITY', '最大圈复杂度', '质量', '方法的圈复杂度超过阈值时告警', 'MAJOR', TRUE, TRUE, 1),
('MAX_METHOD_LENGTH', '最大方法长度', '风格', '方法行数超过阈值时告警', 'MINOR', TRUE, TRUE, 2),
('MAX_FILE_LENGTH', '最大文件长度', '风格', '文件行数超过阈值时告警', 'MINOR', TRUE, TRUE, 3),
('NAMING_CLASS', '类命名规范', '风格', '类名应使用大驼峰命名法', 'MAJOR', TRUE, TRUE, 4),
('NAMING_METHOD', '方法命名规范', '风格', '方法名应使用小驼峰命名法', 'MINOR', TRUE, TRUE, 5),
('MAGIC_NUMBER', '魔法数字', '风格', '避免使用魔法数字，应定义常量', 'MINOR', TRUE, TRUE, 6),
('EMPTY_CATCH', '空catch块', '缺陷', 'catch块不能为空', 'MAJOR', TRUE, TRUE, 7),
('SYSTEM_OUT', 'System.out输出', '风格', '生产代码应使用日志框架而非System.out', 'MAJOR', TRUE, TRUE, 8),
('UNUSED_IMPORT', '未使用的import', '冗余', '应删除未使用的import语句', 'MINOR', TRUE, TRUE, 9),
('NULL_CHECK', '空指针风险', '缺陷', '可能存在空指针异常风险', 'CRITICAL', TRUE, TRUE, 10);

-- ============================================================
-- 表 19: update_artifact_dismissal (同版本号安装包重发提示的忽略记录，固定单行 id=1)
-- ============================================================
CREATE TABLE IF NOT EXISTS update_artifact_dismissal (
    id BIGINT PRIMARY KEY,
    artifact_key VARCHAR(160) NOT NULL,
    dismissed_at TIMESTAMP NOT NULL
);

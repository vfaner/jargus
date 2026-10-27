-- ============================================================
-- 百目 JArgus - SQL Server Database Schema
-- 适用: SQL Server 2016+ （非聚簇索引键上限 1700 字节）
-- Version: 1.0.0
-- 说明: 自增列用 IDENTITY(1,1)（显式 id 写入需 SET IDENTITY_INSERT，
--       由切换流程处理）；文本用 NVARCHAR（Unicode 安全）；布尔用 BIT；
--       T-SQL 无 CREATE TABLE IF NOT EXISTS，本脚本仅在未检测到
--       schema_version 表时由应用执行；不含 database_config 种子行
-- ============================================================

-- ============================================================
-- 表 1: schema_version (表结构版本)
-- ============================================================
CREATE TABLE schema_version (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    version NVARCHAR(32) NOT NULL,
    description NVARCHAR(256),
    applied_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 2: database_config (数据库配置)
-- ============================================================
CREATE TABLE database_config (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(128) NOT NULL,
    db_type NVARCHAR(32) NOT NULL,
    driver_class NVARCHAR(256),
    jdbc_url NVARCHAR(512),
    username NVARCHAR(128),
    password NVARCHAR(512),
    max_pool_size INT DEFAULT 10,
    min_idle INT DEFAULT 5,
    connection_timeout INT DEFAULT 30000,
    dialect NVARCHAR(32),
    driver_jar_path NVARCHAR(512),
    connection_properties NVARCHAR(MAX),
    is_custom BIT DEFAULT 0,
    is_active BIT DEFAULT 0,
    schema_version NVARCHAR(32),
    is_initialized BIT DEFAULT 0,
    sort_order INT DEFAULT 0,
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 3: ai_provider_config (AI厂商配置)
-- ============================================================
CREATE TABLE ai_provider_config (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    provider_name NVARCHAR(64) NOT NULL,
    display_name NVARCHAR(128),
    protocol_type NVARCHAR(32) NOT NULL,
    base_url NVARCHAR(512),
    api_key NVARCHAR(512),
    secret_key NVARCHAR(512),
    default_model NVARCHAR(128),
    available_models NVARCHAR(MAX),
    auth_type NVARCHAR(32) DEFAULT 'BEARER',
    auth_header_name NVARCHAR(128) DEFAULT 'Authorization',
    request_method NVARCHAR(16) DEFAULT 'POST',
    request_headers NVARCHAR(MAX),
    request_body_template NVARCHAR(MAX),
    response_content_path NVARCHAR(256),
    response_error_path NVARCHAR(256),
    timeout_seconds INT DEFAULT 60,
    max_tokens INT,
    is_custom BIT DEFAULT 0,
    is_active BIT DEFAULT 0,
    is_enabled BIT DEFAULT 1,
    sort_order INT DEFAULT 0,
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 4: llm_template (LLM配置模板)
-- ============================================================
CREATE TABLE llm_template (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    template_name NVARCHAR(128) NOT NULL,
    provider_name NVARCHAR(64),
    protocol_type NVARCHAR(32) NOT NULL,
    base_url NVARCHAR(512),
    auth_type NVARCHAR(32) DEFAULT 'BEARER',
    auth_header_name NVARCHAR(128) DEFAULT 'Authorization',
    request_body_template NVARCHAR(MAX),
    response_content_path NVARCHAR(256),
    response_error_path NVARCHAR(256),
    default_model NVARCHAR(128),
    description NVARCHAR(512),
    is_builtin BIT DEFAULT 1,
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 5: scan_task (扫描任务)
-- ============================================================
CREATE TABLE scan_task (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    task_name NVARCHAR(256) NOT NULL,
    project_name NVARCHAR(256),
    source_type NVARCHAR(32) NOT NULL,
    status NVARCHAR(32) DEFAULT 'PENDING',
    total_files INT DEFAULT 0,
    total_lines INT DEFAULT 0,
    blocker_count INT DEFAULT 0,
    critical_count INT DEFAULT 0,
    major_count INT DEFAULT 0,
    minor_count INT DEFAULT 0,
    info_count INT DEFAULT 0,
    total_issues INT DEFAULT 0,
    jdk_version NVARCHAR(32),
    spring_boot_version NVARCHAR(32),
    skip_unit_test BIT DEFAULT 0,
    include_test_code BIT DEFAULT 0,
    enable_ai_review BIT DEFAULT 1,
    ai_issue_count INT DEFAULT 0,
    notify_enabled BIT DEFAULT 0,
    notify_recipient_ids NVARCHAR(512),
    mail_status NVARCHAR(16),
    started_at DATETIME2,
    completed_at DATETIME2,
    duration_seconds BIGINT DEFAULT 0,
    error_message NVARCHAR(MAX),
    snapshot_path NVARCHAR(512),
    report_path NVARCHAR(512),
    created_by NVARCHAR(128),
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 6: scan_issue (扫描问题详情)
-- ============================================================
CREATE TABLE scan_issue (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    task_id BIGINT NOT NULL,
    file_path NVARCHAR(512) NOT NULL,
    file_name NVARCHAR(256),
    line_start INT,
    line_end INT,
    column_start INT,
    column_end INT,
    line_points NVARCHAR(MAX),
    occurrence_count INT DEFAULT 1,
    issue_level NVARCHAR(16) NOT NULL,
    checker_type NVARCHAR(64) NOT NULL,
    checker_name NVARCHAR(128),
    rule_code NVARCHAR(64),
    title NVARCHAR(256) NOT NULL,
    description NVARCHAR(MAX),
    code_snippet NVARCHAR(MAX),
    suggestion NVARCHAR(MAX),
    severity INT DEFAULT 1,
    is_ai_generated BIT DEFAULT 0,
    is_ignored BIT DEFAULT 0,
    ignore_type NVARCHAR(32),
    ignore_reason NVARCHAR(512),
    ai_explanation NVARCHAR(MAX),
    ai_suggestion NVARCHAR(MAX),
    ai_suggestion_at DATETIME2,
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_scan_issue_task_id ON scan_issue (task_id);
CREATE INDEX idx_scan_issue_file_path ON scan_issue (file_path);
CREATE INDEX idx_scan_issue_issue_level ON scan_issue (issue_level);

-- ============================================================
-- 表 7: ignore_rule (强制忽略规则)
-- ============================================================
CREATE TABLE ignore_rule (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    rule_type NVARCHAR(32) NOT NULL,
    rule_code NVARCHAR(64),
    file_pattern NVARCHAR(512),
    file_path NVARCHAR(512),
    line_number INT,
    reason NVARCHAR(512),
    is_enabled BIT DEFAULT 1,
    created_by NVARCHAR(128),
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 8: review_rule (评审规则配置)
-- ============================================================
CREATE TABLE review_rule (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    rule_code NVARCHAR(64) NOT NULL UNIQUE,
    rule_name NVARCHAR(128) NOT NULL,
    rule_category NVARCHAR(64),
    description NVARCHAR(MAX),
    default_level NVARCHAR(16) DEFAULT 'MAJOR',
    is_enabled BIT DEFAULT 1,
    is_builtin BIT DEFAULT 1,
    params NVARCHAR(MAX),
    sort_order INT DEFAULT 0,
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 9: checker_config (检查器配置)
-- ============================================================
CREATE TABLE checker_config (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    checker_code NVARCHAR(64) NOT NULL UNIQUE,
    checker_name NVARCHAR(128) NOT NULL,
    checker_category NVARCHAR(64),
    description NVARCHAR(MAX),
    is_enabled BIT DEFAULT 1,
    is_local BIT DEFAULT 1,
    params NVARCHAR(MAX),
    sort_order INT DEFAULT 0,
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 10: ci_trigger_config (CI触发配置)
-- ============================================================
CREATE TABLE ci_trigger_config (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    config_name NVARCHAR(128) NOT NULL,
    platform NVARCHAR(32),
    platform_url NVARCHAR(256),
    repo_scope NVARCHAR(256),
    webhook_url NVARCHAR(512),
    secret_token NVARCHAR(256),
    repo_username NVARCHAR(128),
    repo_token NVARCHAR(512),
    branch_filter NVARCHAR(256),
    skip_unit_test BIT DEFAULT 0,
    include_test_code BIT DEFAULT 0,
    enable_ai_review BIT DEFAULT 1,
    auto_comment BIT DEFAULT 0,
    notify_enabled BIT DEFAULT 0,
    notify_recipient_ids NVARCHAR(512),
    is_enabled BIT DEFAULT 1,
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 11: ci_scan_record (CI扫描记录)
-- ============================================================
CREATE TABLE ci_scan_record (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    trigger_config_id BIGINT,
    task_id BIGINT,
    platform NVARCHAR(32),
    project_url NVARCHAR(512),
    commit_id NVARCHAR(128),
    branch NVARCHAR(128),
    mr_pr_id NVARCHAR(64),
    mr_pr_title NVARCHAR(256),
    author NVARCHAR(128),
    status NVARCHAR(32) DEFAULT 'PENDING',
    result_url NVARCHAR(512),
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 12: ci_token (CI令牌)
-- ============================================================
CREATE TABLE ci_token (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    token_name NVARCHAR(128) NOT NULL,
    token_value NVARCHAR(256) NOT NULL UNIQUE,
    description NVARCHAR(512),
    is_enabled BIT DEFAULT 1,
    expires_at DATETIME2,
    last_used_at DATETIME2,
    created_by NVARCHAR(128),
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 13: sys_user (系统用户)
-- ============================================================
CREATE TABLE sys_user (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    username NVARCHAR(64) NOT NULL UNIQUE,
    password_hash NVARCHAR(256) NOT NULL,
    nickname NVARCHAR(128),
    role NVARCHAR(32) NOT NULL DEFAULT 'VIEWER',
    source NVARCHAR(32) NOT NULL DEFAULT 'LOCAL',
    is_enabled BIT DEFAULT 1,
    is_password_default BIT DEFAULT 1,
    last_login_at DATETIME2,
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- 默认用户由应用首次启动时创建（BCrypt 加密）：
--   admin / 123456  （管理员，ADMIN）
--   view  / 123456  （只读用户，VIEWER）
-- 见 AuthService.initDefaultUsers()

-- ============================================================
-- 表 14: remote_auth_config (远端登录配置)
-- ============================================================
CREATE TABLE remote_auth_config (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    config_name NVARCHAR(128) NOT NULL,
    auth_type NVARCHAR(32) NOT NULL DEFAULT 'OAUTH2',
    login_url NVARCHAR(512),
    user_info_url NVARCHAR(512),
    token_url NVARCHAR(512),
    client_id NVARCHAR(256),
    client_secret NVARCHAR(512),
    username_field NVARCHAR(64),
    nickname_field NVARCHAR(128),
    role_field NVARCHAR(64),
    role_mapping NVARCHAR(MAX),
    is_enabled BIT DEFAULT 0,
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 15: sys_oper_log (操作日志)
-- ============================================================
CREATE TABLE sys_oper_log (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    username NVARCHAR(64),
    operation NVARCHAR(128),
    method NVARCHAR(16),
    params NVARCHAR(MAX),
    ip NVARCHAR(64),
    status NVARCHAR(16),
    error_msg NVARCHAR(512),
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 16: gate_setting (质量门禁自定义配置，单行 id=1；无行=用 application.yml 默认值)
-- ============================================================
CREATE TABLE gate_setting (
    id BIGINT PRIMARY KEY,
    blocker_weight INT NOT NULL,
    critical_weight INT NOT NULL,
    major_weight INT NOT NULL,
    minor_weight INT NOT NULL,
    info_weight INT NOT NULL,
    pass_score INT NOT NULL,
    blocker_limit INT NOT NULL,
    excellent_score INT NOT NULL,
    good_score INT NOT NULL,
    fair_score INT NOT NULL,
    updated_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 17: mail_sender (发件配置；同一时间仅允许一条 is_enabled=1，由服务层保证)
-- ============================================================
CREATE TABLE mail_sender (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(128) NOT NULL,
    host NVARCHAR(256) NOT NULL,
    port INT NOT NULL DEFAULT 465,
    password NVARCHAR(512),
    from_address NVARCHAR(256) NOT NULL,
    from_alias NVARCHAR(128),
    use_starttls BIT DEFAULT 0,
    use_ssl BIT DEFAULT 1,
    is_enabled BIT DEFAULT 0,
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表 18: mail_recipient (扫描通知邮件收件人)
-- ============================================================
CREATE TABLE mail_recipient (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(128) NOT NULL,
    email NVARCHAR(256) NOT NULL UNIQUE,
    created_at DATETIME2 DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 初始化数据（中文字面量带 N 前缀保证 Unicode 存储）
-- ============================================================

-- 插入 schema 版本
INSERT INTO schema_version (version, description) VALUES ('1.0.0', N'初始版本');

-- 注意：database_config 种子行由切换流程写入，脚本不预置（避免出现双 active）

-- 插入内置 LLM 模板
INSERT INTO llm_template (template_name, provider_name, protocol_type, base_url, auth_type, auth_header_name, request_body_template, response_content_path, response_error_path, default_model, description, is_builtin) VALUES
(N'OpenAI 兼容', 'openai', 'OPENAI_COMPATIBLE', 'https://api.openai.com/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7,"max_tokens":4096}',
 'choices[0].message.content', 'error.message', 'gpt-4', N'OpenAI 兼容协议通用模板', 1),
(N'阿里百炼', 'qwen', 'OPENAI_COMPATIBLE', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'message', 'qwen-plus', N'阿里百炼通义千问', 1),
(N'火山方舟', 'doubao', 'OPENAI_COMPATIBLE', 'https://ark.cn-beijing.volces.com/api/v3', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'doubao-pro', N'火山引擎方舟', 1),
(N'DeepSeek', 'deepseek', 'OPENAI_COMPATIBLE', 'https://api.deepseek.com/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'deepseek-chat', 'DeepSeek', 1),
(N'Kimi', 'kimi', 'OPENAI_COMPATIBLE', 'https://api.moonshot.cn/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'moonshot-v1-8k', N'Kimi 月之暗面', 1),
(N'智谱 GLM', 'zhipu', 'OPENAI_COMPATIBLE', 'https://open.bigmodel.cn/api/paas/v4', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'glm-4', N'智谱清言', 1),
(N'百度千帆', 'qianfan', 'OPENAI_COMPATIBLE', 'https://qianfan.baidubce.com/v2', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'ernie-4.0-turbo-8k', N'百度千帆（OpenAI 兼容端点）', 1),
(N'Gemini', 'gemini', 'OPENAI_COMPATIBLE', 'https://generativelanguage.googleapis.com/v1beta/openai', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'gemini-2.0-flash', N'Google Gemini（OpenAI 兼容端点）', 1),
(N'Claude', 'claude', 'ANTHROPIC', 'https://api.anthropic.com/v1', 'API_KEY_HEADER', 'x-api-key',
 NULL,
 'content[0].text', 'error.message', 'claude-3-5-sonnet-latest', N'Anthropic Claude（x-api-key 鉴权）', 1),
(N'Ollama', 'ollama', 'OPENAI_COMPATIBLE', 'http://localhost:11434/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'llama3', N'Ollama 本地部署（OpenAI 兼容）', 1),
(N'vLLM', 'vllm', 'OPENAI_COMPATIBLE', 'http://localhost:8000/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'default', N'vLLM 私有部署（OpenAI 兼容）', 1),
(N'LocalAI', 'localai', 'OPENAI_COMPATIBLE', 'http://localhost:8080/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'gpt4all-j', N'LocalAI 本地部署（OpenAI 兼容）', 1);

-- 插入默认检查器配置
INSERT INTO checker_config (checker_code, checker_name, checker_category, description, is_enabled, is_local, sort_order) VALUES
('compilation', N'编译诊断', N'基础', N'使用 javax.tools 进行编译，获取编译错误和警告', 1, 1, 1),
('nullpointer', N'空指针检测', N'缺陷', N'基于AST分析潜在的空指针风险', 1, 1, 2),
('unused_method', N'未使用方法检测', N'冗余', N'基于调用图分析未被调用的方法', 1, 1, 3),
('deprecated_method', N'废弃方法检测', N'兼容性', N'检测使用了 @Deprecated 注解的方法', 1, 1, 4),
('complexity', N'圈复杂度检测', N'质量', N'检测圈复杂度超标的方法', 1, 1, 5),
('naming', N'命名规范检查', N'风格', N'检查类、方法、变量命名是否符合规范', 1, 1, 6),
('code_style', N'代码风格检查', N'风格', N'检查代码风格问题，如魔法数字、过长方法等', 1, 1, 7),
('duplicate_code', N'重复代码检测', N'冗余', N'检测重复的代码块', 1, 1, 8),
('exception_handling', N'异常处理检查', N'缺陷', N'检查异常处理不当的问题', 1, 1, 9),
('resource_leak', N'资源泄露检测', N'缺陷', N'检测未正确关闭的资源', 1, 1, 10),
('security', N'安全漏洞检测', N'安全', N'检测常见的安全漏洞', 1, 1, 11),
('concurrency', N'并发问题检测', N'并发', N'检测多线程并发问题', 1, 1, 12),
('performance', N'性能问题检测', N'性能', N'检测常见性能问题', 1, 1, 13),
('spring_best_practice', N'Spring最佳实践', N'框架', N'检查Spring使用的最佳实践', 1, 1, 14),
('ai_semantic', N'AI语义评审', 'AI', N'使用AI进行语义级代码评审', 1, 0, 15),
('ai_security', N'AI安全评审', 'AI', N'使用AI进行安全漏洞深度分析', 1, 0, 16),
('ai_design', N'AI设计评审', 'AI', N'使用AI进行代码设计评审', 1, 0, 17),
('architecture', N'架构约束检查', N'架构', N'检查分层架构约束：控制器不得跨层访问DAO、下层不得反向依赖上层、实体不得泄漏到接口层', 1, 1, 18),
('dependency_vuln', N'依赖漏洞扫描', N'依赖', N'解析pom.xml/build.gradle依赖，与内置漏洞库匹配已知CVE（可选OSV在线增强）', 1, 1, 19);

-- 插入默认评审规则
INSERT INTO review_rule (rule_code, rule_name, rule_category, description, default_level, is_enabled, is_builtin, sort_order) VALUES
('MAX_COMPLEXITY', N'最大圈复杂度', N'质量', N'方法的圈复杂度超过阈值时告警', 'MAJOR', 1, 1, 1),
('MAX_METHOD_LENGTH', N'最大方法长度', N'风格', N'方法行数超过阈值时告警', 'MINOR', 1, 1, 2),
('MAX_FILE_LENGTH', N'最大文件长度', N'风格', N'文件行数超过阈值时告警', 'MINOR', 1, 1, 3),
('NAMING_CLASS', N'类命名规范', N'风格', N'类名应使用大驼峰命名法', 'MAJOR', 1, 1, 4),
('NAMING_METHOD', N'方法命名规范', N'风格', N'方法名应使用小驼峰命名法', 'MINOR', 1, 1, 5),
('MAGIC_NUMBER', N'魔法数字', N'风格', N'避免使用魔法数字，应定义常量', 'MINOR', 1, 1, 6),
('EMPTY_CATCH', N'空catch块', N'缺陷', N'catch块不能为空', 'MAJOR', 1, 1, 7),
('SYSTEM_OUT', N'System.out输出', N'风格', N'生产代码应使用日志框架而非System.out', 'MAJOR', 1, 1, 8),
('UNUSED_IMPORT', N'未使用的import', N'冗余', N'应删除未使用的import语句', 'MINOR', 1, 1, 9),
('NULL_CHECK', N'空指针风险', N'缺陷', N'可能存在空指针异常风险', 'CRITICAL', 1, 1, 10);

-- ============================================================
-- 表 19: update_artifact_dismissal (同版本号安装包重发提示的忽略记录，固定单行 id=1)
-- ============================================================
CREATE TABLE update_artifact_dismissal (
    id BIGINT PRIMARY KEY,
    artifact_key NVARCHAR(160) NOT NULL,
    dismissed_at DATETIME2 NOT NULL
);

-- ============================================================
-- 表 20: sso_config (企业统一认证配置，固定单行 id=1；无行=未配置)
-- ============================================================
CREATE TABLE sso_config (
    id BIGINT PRIMARY KEY,
    is_enabled BIT DEFAULT 0,
    auth_mode NVARCHAR(16) DEFAULT 'TICKET' NOT NULL,
    admin_users NVARCHAR(1024),
    login_entry_url NVARCHAR(512),
    logout_redirect_url NVARCHAR(512),
    local_login_enabled BIT DEFAULT 1,
    user_header NVARCHAR(128),
    admin_header NVARCHAR(128),
    admin_value NVARCHAR(128),
    secret_header_name NVARCHAR(128),
    secret_header_value NVARCHAR(512),
    validate_url NVARCHAR(512),
    validate_method NVARCHAR(8) DEFAULT 'GET',
    ticket_param NVARCHAR(64) DEFAULT 'ticket',
    success_field NVARCHAR(128),
    success_value NVARCHAR(128),
    user_field NVARCHAR(128),
    admin_field NVARCHAR(128),
    timeout_ms INT DEFAULT 5000,
    allow_insecure_http BIT DEFAULT 0,
    updated_at DATETIME2 DEFAULT CURRENT_TIMESTAMP
);

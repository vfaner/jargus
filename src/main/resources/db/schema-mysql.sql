-- ============================================================
-- 百目 JArgus - MySQL Database Schema
-- Version: 1.0.0
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 表 1: schema_version
-- ============================================================
CREATE TABLE IF NOT EXISTS schema_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    version VARCHAR(32) NOT NULL,
    description VARCHAR(256),
    applied_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 表 2: database_config
-- ============================================================
CREATE TABLE IF NOT EXISTS database_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL COMMENT '配置名称',
    db_type VARCHAR(32) NOT NULL COMMENT '数据库类型',
    driver_class VARCHAR(256) COMMENT '驱动类名',
    jdbc_url VARCHAR(512) COMMENT 'JDBC URL',
    username VARCHAR(128) COMMENT '用户名',
    password VARCHAR(512) COMMENT '密码（AES加密）',
    max_pool_size INT DEFAULT 10 COMMENT '最大连接数',
    min_idle INT DEFAULT 5 COMMENT '最小空闲连接',
    connection_timeout INT DEFAULT 30000 COMMENT '连接超时(ms)',
    dialect VARCHAR(32) COMMENT 'SQL方言',
    driver_jar_path VARCHAR(512) COMMENT '驱动JAR路径',
    connection_properties TEXT COMMENT '额外连接属性 JSON',
    is_custom TINYINT(1) DEFAULT 0 COMMENT '是否自定义',
    is_active TINYINT(1) DEFAULT 0 COMMENT '是否当前启用',
    schema_version VARCHAR(32) COMMENT '表结构版本',
    is_initialized TINYINT(1) DEFAULT 0 COMMENT '是否已初始化',
    sort_order INT DEFAULT 0 COMMENT '排序',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库配置';

-- ============================================================
-- 表 3: ai_provider_config
-- ============================================================
CREATE TABLE IF NOT EXISTS ai_provider_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_name VARCHAR(64) NOT NULL COMMENT '厂商名称',
    display_name VARCHAR(128) COMMENT '显示名称',
    protocol_type VARCHAR(32) NOT NULL COMMENT '协议类型',
    base_url VARCHAR(512) COMMENT 'Base URL',
    api_key VARCHAR(512) COMMENT 'API Key（加密）',
    secret_key VARCHAR(512) COMMENT 'Secret Key（加密）',
    default_model VARCHAR(128) COMMENT '默认模型',
    available_models TEXT COMMENT '可用模型 JSON',
    auth_type VARCHAR(32) DEFAULT 'BEARER' COMMENT '认证方式',
    auth_header_name VARCHAR(128) DEFAULT 'Authorization' COMMENT '认证头名称',
    request_method VARCHAR(16) DEFAULT 'POST' COMMENT '请求方法',
    request_headers TEXT COMMENT '自定义请求头 JSON',
    request_body_template TEXT COMMENT '请求体模板',
    response_content_path VARCHAR(256) COMMENT '响应内容路径',
    response_error_path VARCHAR(256) COMMENT '错误信息路径',
    timeout_seconds INT DEFAULT 60 COMMENT '超时时间(秒)',
    max_tokens INT COMMENT '最大Token数限制（NULL不限制）',
    is_custom TINYINT(1) DEFAULT 0 COMMENT '是否自定义',
    is_active TINYINT(1) DEFAULT 0 COMMENT '是否当前启用',
    is_enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    sort_order INT DEFAULT 0 COMMENT '排序',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI厂商配置';

-- ============================================================
-- 表 4: llm_template
-- ============================================================
CREATE TABLE IF NOT EXISTS llm_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    template_name VARCHAR(128) NOT NULL COMMENT '模板名称',
    provider_name VARCHAR(64) COMMENT '厂商名称',
    protocol_type VARCHAR(32) NOT NULL COMMENT '协议类型',
    base_url VARCHAR(512) COMMENT '默认 Base URL',
    auth_type VARCHAR(32) DEFAULT 'BEARER' COMMENT '认证方式',
    auth_header_name VARCHAR(128) DEFAULT 'Authorization' COMMENT '认证头名称',
    request_body_template TEXT COMMENT '请求体模板',
    response_content_path VARCHAR(256) COMMENT '响应内容路径',
    response_error_path VARCHAR(256) COMMENT '错误路径',
    default_model VARCHAR(128) COMMENT '默认模型',
    description VARCHAR(512) COMMENT '说明',
    is_builtin TINYINT(1) DEFAULT 1 COMMENT '是否内置',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LLM配置模板';

-- ============================================================
-- 表 5: scan_task
-- ============================================================
CREATE TABLE IF NOT EXISTS scan_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_name VARCHAR(256) NOT NULL COMMENT '任务名称',
    project_name VARCHAR(256) COMMENT '项目名称',
    source_type VARCHAR(32) NOT NULL COMMENT '来源类型',
    status VARCHAR(32) DEFAULT 'PENDING' COMMENT '状态',
    total_files INT DEFAULT 0 COMMENT '总文件数',
    total_lines INT DEFAULT 0 COMMENT '总代码行数',
    blocker_count INT DEFAULT 0 COMMENT '阻断BLOCKER数量',
    critical_count INT DEFAULT 0 COMMENT '严重CRITICAL数量',
    major_count INT DEFAULT 0 COMMENT '主要MAJOR数量',
    minor_count INT DEFAULT 0 COMMENT '次要MINOR数量',
    info_count INT DEFAULT 0 COMMENT '提示INFO数量',
    total_issues INT DEFAULT 0 COMMENT '问题总数',
    jdk_version VARCHAR(32) COMMENT 'JDK版本',
    spring_boot_version VARCHAR(32) COMMENT 'Spring Boot版本',
    skip_unit_test TINYINT(1) DEFAULT 0 COMMENT '跳过单元测试',
    include_test_code TINYINT(1) DEFAULT 0 COMMENT '包含测试代码',
    enable_ai_review TINYINT(1) DEFAULT 1 COMMENT '启用AI评审',
    ai_issue_count INT DEFAULT 0 COMMENT 'AI发现问题数',
    notify_enabled TINYINT(1) DEFAULT 0 COMMENT '扫描完成后发送通知邮件',
    notify_recipient_ids VARCHAR(512) COMMENT '通知收件人 id 逗号串',
    mail_status VARCHAR(16) COMMENT '通知邮件状态 SENT/FAILED，空=未发',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    duration_seconds BIGINT DEFAULT 0 COMMENT '耗时(秒)',
    error_message TEXT COMMENT '错误信息',
    snapshot_path VARCHAR(512) COMMENT '源码快照路径',
    report_path VARCHAR(512) COMMENT '报告路径',
    created_by VARCHAR(128) COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='扫描任务';

-- ============================================================
-- 表 6: scan_issue
-- ============================================================
CREATE TABLE IF NOT EXISTS scan_issue (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL COMMENT '扫描任务ID',
    file_path VARCHAR(512) NOT NULL COMMENT '文件路径',
    file_name VARCHAR(256) COMMENT '文件名',
    line_start INT COMMENT '起始行号',
    line_end INT COMMENT '结束行号',
    column_start INT COMMENT '起始列',
    column_end INT COMMENT '结束列',
    line_points TEXT COMMENT '同文件同规则合并后的问题位置JSON [[起,止]...]',
    occurrence_count INT DEFAULT 1 COMMENT '原始命中点数',
    issue_level VARCHAR(16) NOT NULL COMMENT '问题级别 BLOCKER/CRITICAL/MAJOR/MINOR/INFO',
    checker_type VARCHAR(64) NOT NULL COMMENT '检查器类型',
    checker_name VARCHAR(128) COMMENT '检查器名称',
    rule_code VARCHAR(64) COMMENT '规则编码',
    title VARCHAR(256) NOT NULL COMMENT '问题标题',
    description TEXT COMMENT '问题描述',
    code_snippet TEXT COMMENT '代码片段',
    suggestion TEXT COMMENT '修复建议',
    severity INT DEFAULT 1 COMMENT '严重度秩 1-5（INFO=1..BLOCKER=5）',
    is_ai_generated TINYINT(1) DEFAULT 0 COMMENT '是否AI生成',
    is_ignored TINYINT(1) DEFAULT 0 COMMENT '是否已忽略',
    ignore_type VARCHAR(32) COMMENT '忽略类型',
    ignore_reason VARCHAR(512) COMMENT '忽略原因',
    ai_explanation TEXT COMMENT 'AI解释',
    ai_suggestion TEXT COMMENT 'AI增强修复建议（深度评审按需生成）',
    ai_suggestion_at DATETIME COMMENT 'AI增强建议生成时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task_id (task_id),
    INDEX idx_file_path (file_path(200)),
    INDEX idx_issue_level (issue_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='扫描问题详情';

-- ============================================================
-- 表 7: ignore_rule
-- ============================================================
CREATE TABLE IF NOT EXISTS ignore_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_type VARCHAR(32) NOT NULL COMMENT '忽略类型',
    rule_code VARCHAR(64) COMMENT '规则编码',
    file_pattern VARCHAR(512) COMMENT '文件匹配模式',
    file_path VARCHAR(512) COMMENT '文件路径',
    line_number INT COMMENT '行号',
    reason VARCHAR(512) COMMENT '忽略原因',
    is_enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    created_by VARCHAR(128) COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='强制忽略规则';

-- ============================================================
-- 表 8: review_rule
-- ============================================================
CREATE TABLE IF NOT EXISTS review_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_code VARCHAR(64) NOT NULL UNIQUE COMMENT '规则编码',
    rule_name VARCHAR(128) NOT NULL COMMENT '规则名称',
    rule_category VARCHAR(64) COMMENT '规则分类',
    description TEXT COMMENT '规则描述',
    default_level VARCHAR(16) DEFAULT 'MAJOR' COMMENT '默认级别 BLOCKER/CRITICAL/MAJOR/MINOR/INFO',
    is_enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    is_builtin TINYINT(1) DEFAULT 1 COMMENT '是否内置',
    params TEXT COMMENT '参数配置 JSON',
    sort_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评审规则配置';

-- ============================================================
-- 表 9: checker_config
-- ============================================================
CREATE TABLE IF NOT EXISTS checker_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    checker_code VARCHAR(64) NOT NULL UNIQUE COMMENT '检查器编码',
    checker_name VARCHAR(128) NOT NULL COMMENT '检查器名称',
    checker_category VARCHAR(64) COMMENT '检查器分类',
    description TEXT COMMENT '描述',
    is_enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    is_local TINYINT(1) DEFAULT 1 COMMENT '是否本地检查',
    params TEXT COMMENT '参数配置 JSON',
    sort_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='检查器配置';

-- ============================================================
-- 表 10: ci_trigger_config
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_trigger_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_name VARCHAR(128) NOT NULL COMMENT '配置名称',
    platform VARCHAR(32) COMMENT '平台',
    platform_url VARCHAR(256) COMMENT '代码平台地址（官方云或企业自建）',
    repo_scope VARCHAR(256) COMMENT '仓库范围（owner/repo，空=不限制）',
    webhook_url VARCHAR(512) COMMENT 'Webhook URL',
    secret_token VARCHAR(256) COMMENT '密钥令牌（AES 加密存储）',
    repo_username VARCHAR(128) COMMENT '私有仓库克隆用户名',
    repo_token VARCHAR(512) COMMENT '私有仓库访问令牌（AES 加密存储）',
    branch_filter VARCHAR(256) COMMENT '分支过滤',
    skip_unit_test TINYINT(1) DEFAULT 0,
    include_test_code TINYINT(1) DEFAULT 0,
    enable_ai_review TINYINT(1) DEFAULT 1,
    auto_comment TINYINT(1) DEFAULT 0 COMMENT '自动评论',
    notify_enabled TINYINT(1) DEFAULT 0 COMMENT '扫描完成后发送通知邮件',
    notify_recipient_ids VARCHAR(512) COMMENT '通知收件人 id 逗号串',
    is_enabled TINYINT(1) DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CI触发配置';

-- ============================================================
-- 表 11: ci_scan_record
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_scan_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trigger_config_id BIGINT COMMENT '触发配置ID',
    task_id BIGINT COMMENT '扫描任务ID',
    platform VARCHAR(32) COMMENT '平台',
    project_url VARCHAR(512) COMMENT '项目URL',
    commit_id VARCHAR(128) COMMENT '提交ID',
    branch VARCHAR(128) COMMENT '分支',
    mr_pr_id VARCHAR(64) COMMENT 'MR/PR ID',
    mr_pr_title VARCHAR(256) COMMENT 'MR/PR 标题',
    author VARCHAR(128) COMMENT '作者',
    status VARCHAR(32) DEFAULT 'PENDING',
    result_url VARCHAR(512) COMMENT '结果链接',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CI扫描记录';

-- ============================================================
-- 表 12: ci_token
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_token (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    token_name VARCHAR(128) NOT NULL COMMENT '令牌名称',
    token_value VARCHAR(256) NOT NULL UNIQUE COMMENT '令牌值',
    description VARCHAR(512) COMMENT '描述',
    is_enabled TINYINT(1) DEFAULT 1,
    expires_at DATETIME COMMENT '过期时间',
    last_used_at DATETIME COMMENT '最后使用时间',
    created_by VARCHAR(128) COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CI令牌';

-- ============================================================
-- 表 13: sys_user (系统用户)
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL COMMENT '用户名',
    password_hash VARCHAR(256) NOT NULL COMMENT '密码哈希（BCrypt）',
    nickname VARCHAR(128) COMMENT '昵称',
    role VARCHAR(32) NOT NULL DEFAULT 'VIEWER' COMMENT '角色',
    source VARCHAR(32) NOT NULL DEFAULT 'LOCAL' COMMENT '来源 LOCAL/远端登录',
    is_enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    is_password_default TINYINT(1) DEFAULT 1 COMMENT '是否仍为默认密码',
    last_login_at DATETIME COMMENT '最后登录时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户';

-- 默认用户由应用首次启动时创建（BCrypt 加密）：
--   admin / 123456  （管理员，ADMIN）
--   view  / 123456  （只读用户，VIEWER）
-- 见 AuthService.initDefaultUsers()

-- ============================================================
-- 表 14: remote_auth_config (远端登录配置)
-- ============================================================
CREATE TABLE IF NOT EXISTS remote_auth_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_name VARCHAR(128) NOT NULL COMMENT '配置名称',
    auth_type VARCHAR(32) NOT NULL DEFAULT 'OAUTH2' COMMENT '认证类型',
    login_url VARCHAR(512) COMMENT '登录地址',
    user_info_url VARCHAR(512) COMMENT '用户信息地址',
    token_url VARCHAR(512) COMMENT 'Token 地址',
    client_id VARCHAR(256) COMMENT '客户端 ID',
    client_secret VARCHAR(512) COMMENT '客户端密钥（AES 加密存储）',
    username_field VARCHAR(64) COMMENT '用户名字段',
    nickname_field VARCHAR(128) COMMENT '昵称字段',
    role_field VARCHAR(64) COMMENT '角色字段',
    role_mapping TEXT COMMENT '角色映射 JSON',
    is_enabled TINYINT(1) DEFAULT 0 COMMENT '是否启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='远端登录配置';

-- ============================================================
-- 表 15: sys_oper_log (操作日志)
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_oper_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) COMMENT '操作人',
    operation VARCHAR(128) COMMENT '操作描述',
    method VARCHAR(16) COMMENT 'HTTP 方法',
    params TEXT COMMENT '请求参数 JSON',
    ip VARCHAR(64) COMMENT '来源 IP',
    status VARCHAR(16) COMMENT '状态',
    error_msg VARCHAR(512) COMMENT '错误信息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志';

-- ============================================================
-- 表 16: gate_setting (质量门禁自定义配置，单行 id=1；无行=用 application.yml 默认值)
-- ============================================================
CREATE TABLE IF NOT EXISTS gate_setting (
    id BIGINT PRIMARY KEY COMMENT '固定为 1',
    blocker_weight INT NOT NULL COMMENT '阻断每条扣分',
    critical_weight INT NOT NULL COMMENT '严重每条扣分',
    major_weight INT NOT NULL COMMENT '主要每条扣分',
    minor_weight INT NOT NULL COMMENT '次要每条扣分',
    info_weight INT NOT NULL COMMENT '提示每条扣分',
    pass_score INT NOT NULL COMMENT '通过门禁最低评分',
    blocker_limit INT NOT NULL COMMENT '通过门禁允许的最大阻断数',
    excellent_score INT NOT NULL COMMENT '优秀等级下界',
    good_score INT NOT NULL COMMENT '良好等级下界',
    fair_score INT NOT NULL COMMENT '一般等级下界',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '最后修改时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质量门禁配置';

-- ============================================================
-- 表 17: mail_sender (发件配置；同一时间仅允许一条 is_enabled=1，由服务层保证)
-- ============================================================
CREATE TABLE IF NOT EXISTS mail_sender (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL COMMENT '配置名称',
    host VARCHAR(256) NOT NULL COMMENT 'SMTP 主机',
    port INT NOT NULL DEFAULT 465 COMMENT 'SMTP 端口',
    password VARCHAR(512) COMMENT '密码/授权码（AES 加密存储；空=无鉴权）',
    from_address VARCHAR(256) NOT NULL COMMENT '发件邮箱（完整地址，兼作 SMTP 认证账号）',
    from_alias VARCHAR(128) COMMENT '发件人别名',
    use_starttls TINYINT(1) DEFAULT 0 COMMENT '使用 STARTTLS',
    use_ssl TINYINT(1) DEFAULT 1 COMMENT '使用 SSL',
    is_enabled TINYINT(1) DEFAULT 0 COMMENT '启用（服务层保证仅一个）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='发件配置';

-- ============================================================
-- 表 18: mail_recipient (扫描通知邮件收件人)
-- ============================================================
CREATE TABLE IF NOT EXISTS mail_recipient (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL COMMENT '姓名',
    email VARCHAR(256) NOT NULL COMMENT '邮箱',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_mail_recipient_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知收件人';

-- ============================================================
-- 初始化数据
-- ============================================================

-- 插入 schema 版本（与 schema-h2.sql 保持一致）
INSERT INTO schema_version (version, description) VALUES ('1.0.0', '初始版本');

-- 注意：database_config 种子行由切换流程写入，脚本不预置（避免出现双 active）

-- ============================================================
-- 内置 LLM 模板种子数据（与 schema-h2.sql 保持一致，仅 OpenAI 兼容 / Anthropic 两种协议）
-- ============================================================
INSERT INTO llm_template (template_name, provider_name, protocol_type, base_url, auth_type, auth_header_name, request_body_template, response_content_path, response_error_path, default_model, description, is_builtin) VALUES
('OpenAI 兼容', 'openai', 'OPENAI_COMPATIBLE', 'https://api.openai.com/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7,"max_tokens":4096}',
 'choices[0].message.content', 'error.message', 'gpt-4', 'OpenAI 兼容协议通用模板', 1),
('阿里百炼', 'qwen', 'OPENAI_COMPATIBLE', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'message', 'qwen-plus', '阿里百炼通义千问', 1),
('火山方舟', 'doubao', 'OPENAI_COMPATIBLE', 'https://ark.cn-beijing.volces.com/api/v3', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'doubao-pro', '火山引擎方舟', 1),
('DeepSeek', 'deepseek', 'OPENAI_COMPATIBLE', 'https://api.deepseek.com/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'deepseek-chat', 'DeepSeek', 1),
('Kimi', 'kimi', 'OPENAI_COMPATIBLE', 'https://api.moonshot.cn/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'moonshot-v1-8k', 'Kimi 月之暗面', 1),
('智谱 GLM', 'zhipu', 'OPENAI_COMPATIBLE', 'https://open.bigmodel.cn/api/paas/v4', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'glm-4', '智谱清言', 1),
('百度千帆', 'qianfan', 'OPENAI_COMPATIBLE', 'https://qianfan.baidubce.com/v2', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'ernie-4.0-turbo-8k', '百度千帆（OpenAI 兼容端点）', 1),
('Gemini', 'gemini', 'OPENAI_COMPATIBLE', 'https://generativelanguage.googleapis.com/v1beta/openai', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'gemini-2.0-flash', 'Google Gemini（OpenAI 兼容端点）', 1),
('Claude', 'claude', 'ANTHROPIC', 'https://api.anthropic.com/v1', 'API_KEY_HEADER', 'x-api-key',
 NULL,
 'content[0].text', 'error.message', 'claude-3-5-sonnet-latest', 'Anthropic Claude（x-api-key 鉴权）', 1),
('Ollama', 'ollama', 'OPENAI_COMPATIBLE', 'http://localhost:11434/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'llama3', 'Ollama 本地部署（OpenAI 兼容）', 1),
('vLLM', 'vllm', 'OPENAI_COMPATIBLE', 'http://localhost:8000/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'default', 'vLLM 私有部署（OpenAI 兼容）', 1),
('LocalAI', 'localai', 'OPENAI_COMPATIBLE', 'http://localhost:8080/v1', 'BEARER', 'Authorization',
 '{"model":"${model}","messages":[{"role":"system","content":"${system_prompt}"},{"role":"user","content":"${user_prompt}"}],"temperature":0.7}',
 'choices[0].message.content', 'error.message', 'gpt4all-j', 'LocalAI 本地部署（OpenAI 兼容）', 1);

-- 插入默认检查器配置（与 schema-h2.sql 保持一致）
INSERT INTO checker_config (checker_code, checker_name, checker_category, description, is_enabled, is_local, sort_order) VALUES
('compilation', '编译诊断', '基础', '使用 javax.tools 进行编译，获取编译错误和警告', 1, 1, 1),
('nullpointer', '空指针检测', '缺陷', '基于AST分析潜在的空指针风险', 1, 1, 2),
('unused_method', '未使用方法检测', '冗余', '基于调用图分析未被调用的方法', 1, 1, 3),
('deprecated_method', '废弃方法检测', '兼容性', '检测使用了 @Deprecated 注解的方法', 1, 1, 4),
('complexity', '圈复杂度检测', '质量', '检测圈复杂度超标的方法', 1, 1, 5),
('naming', '命名规范检查', '风格', '检查类、方法、变量命名是否符合规范', 1, 1, 6),
('code_style', '代码风格检查', '风格', '检查代码风格问题，如魔法数字、过长方法等', 1, 1, 7),
('duplicate_code', '重复代码检测', '冗余', '检测重复的代码块', 1, 1, 8),
('exception_handling', '异常处理检查', '缺陷', '检查异常处理不当的问题', 1, 1, 9),
('resource_leak', '资源泄露检测', '缺陷', '检测未正确关闭的资源', 1, 1, 10),
('security', '安全漏洞检测', '安全', '检测常见的安全漏洞', 1, 1, 11),
('concurrency', '并发问题检测', '并发', '检测多线程并发问题', 1, 1, 12),
('performance', '性能问题检测', '性能', '检测常见性能问题', 1, 1, 13),
('spring_best_practice', 'Spring最佳实践', '框架', '检查Spring使用的最佳实践', 1, 1, 14),
('ai_semantic', 'AI语义评审', 'AI', '使用AI进行语义级代码评审', 1, 0, 15),
('ai_security', 'AI安全评审', 'AI', '使用AI进行安全漏洞深度分析', 1, 0, 16),
('ai_design', 'AI设计评审', 'AI', '使用AI进行代码设计评审', 1, 0, 17),
('architecture', '架构约束检查', '架构', '检查分层架构约束：控制器不得跨层访问DAO、下层不得反向依赖上层、实体不得泄漏到接口层', 1, 1, 18),
('dependency_vuln', '依赖漏洞扫描', '依赖', '解析pom.xml/build.gradle依赖，与内置漏洞库匹配已知CVE（可选OSV在线增强）', 1, 1, 19);

-- 插入默认评审规则（与 schema-h2.sql 保持一致）
INSERT INTO review_rule (rule_code, rule_name, rule_category, description, default_level, is_enabled, is_builtin, sort_order) VALUES
('MAX_COMPLEXITY', '最大圈复杂度', '质量', '方法的圈复杂度超过阈值时告警', 'MAJOR', 1, 1, 1),
('MAX_METHOD_LENGTH', '最大方法长度', '风格', '方法行数超过阈值时告警', 'MINOR', 1, 1, 2),
('MAX_FILE_LENGTH', '最大文件长度', '风格', '文件行数超过阈值时告警', 'MINOR', 1, 1, 3),
('NAMING_CLASS', '类命名规范', '风格', '类名应使用大驼峰命名法', 'MAJOR', 1, 1, 4),
('NAMING_METHOD', '方法命名规范', '风格', '方法名应使用小驼峰命名法', 'MINOR', 1, 1, 5),
('MAGIC_NUMBER', '魔法数字', '风格', '避免使用魔法数字，应定义常量', 'MINOR', 1, 1, 6),
('EMPTY_CATCH', '空catch块', '缺陷', 'catch块不能为空', 'MAJOR', 1, 1, 7),
('SYSTEM_OUT', 'System.out输出', '风格', '生产代码应使用日志框架而非System.out', 'MAJOR', 1, 1, 8),
('UNUSED_IMPORT', '未使用的import', '冗余', '应删除未使用的import语句', 'MINOR', 1, 1, 9),
('NULL_CHECK', '空指针风险', '缺陷', '可能存在空指针异常风险', 'CRITICAL', 1, 1, 10);

-- ============================================================
-- 表 19: update_artifact_dismissal (同版本号安装包重发提示的忽略记录，固定单行 id=1)
-- ============================================================
CREATE TABLE IF NOT EXISTS update_artifact_dismissal (
    id BIGINT PRIMARY KEY COMMENT '固定为 1',
    artifact_key VARCHAR(160) NOT NULL COMMENT 'sha256:<hex> 或 size:<bytes>',
    dismissed_at DATETIME NOT NULL COMMENT '忽略时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='安装包重发提示忽略记录';

-- ============================================================
-- 表 20: sso_config (企业统一认证配置，固定单行 id=1；无行=未配置)
-- ============================================================
CREATE TABLE IF NOT EXISTS sso_config (
    id BIGINT PRIMARY KEY COMMENT '固定为 1',
    is_enabled BOOLEAN DEFAULT FALSE COMMENT 'SSO 总开关',
    auth_mode VARCHAR(16) DEFAULT 'TICKET' NOT NULL COMMENT 'TICKET / HEADER',
    admin_users VARCHAR(1024) COMMENT '管理员用户名白名单，逗号分隔',
    login_entry_url VARCHAR(512) COMMENT '企业登录中心地址',
    logout_redirect_url VARCHAR(512) COMMENT '登出后回跳地址',
    local_login_enabled BOOLEAN DEFAULT TRUE COMMENT '是否允许本地账号密码登录',
    user_header VARCHAR(128) COMMENT 'HEADER 模式用户名头',
    admin_header VARCHAR(128) COMMENT 'HEADER 模式管理员标记头',
    admin_value VARCHAR(128) COMMENT '管理员标记匹配值',
    secret_header_name VARCHAR(128) COMMENT '共享密钥头名',
    secret_header_value VARCHAR(512) COMMENT '共享密钥（加密存储）',
    validate_url VARCHAR(512) COMMENT '票据校验接口',
    validate_method VARCHAR(8) DEFAULT 'GET' COMMENT 'GET / POST',
    ticket_param VARCHAR(64) DEFAULT 'ticket' COMMENT '票据参数名',
    success_field VARCHAR(128) COMMENT '成功标志字段路径',
    success_value VARCHAR(128) COMMENT '成功标志匹配值',
    user_field VARCHAR(128) COMMENT '用户名字段路径',
    admin_field VARCHAR(128) COMMENT '管理员标记字段路径',
    timeout_ms INT DEFAULT 5000 COMMENT '校验超时（毫秒）',
    allow_insecure_http BOOLEAN DEFAULT FALSE COMMENT '是否允许 http 端点',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业统一认证配置';

SET FOREIGN_KEY_CHECKS = 1;

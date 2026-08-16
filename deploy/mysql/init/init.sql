-- =============================================================
-- Token Limit 数据库初始化脚本（PRD V5.0）
-- 数据库：tokenlimit
-- 字符集：utf8mb4
-- 模型：团队(Team) -> 用户(User) -> API Key
-- 配额：责任链拦截（team-balance 团队余额 / user-balance 个人余额 / usage-period 周期用量，可配置）+ 预计算开关（开启时调用前原子预扣 jtokkit 预估量，结束后回滚预扣、按真实值扣减余额）
-- 角色：ADMIN / TEAM_ADMIN / USER
-- 状态值：统一 ENABLED / DISABLED（API Key 额外支持 EXPIRED / REVOKED）
-- 网关：OpenAI Compatible Proxy + Provider 凭证(GLOBAL/TEAM) + 团队模型策略
-- 预估：jtokkit（estimated_*），异常计费检测（anomaly_*）
-- =============================================================

CREATE DATABASE IF NOT EXISTS `tokenlimit`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `tokenlimit`;

-- -------------------------------------------------------------
-- 1. tl_team 团队（核心预算池 / 成本中心 / 账号边界 / 密钥边界）
--    team_type: TEAM / DEPARTMENT / APPLICATION / PROJECT / CUSTOMER / COST_CENTER
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_team` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `team_code`     VARCHAR(64)  NOT NULL COMMENT '团队编码，如 team-rd/team-cs',
  `team_name`     VARCHAR(128) NOT NULL COMMENT '团队名称',
  `team_type`     VARCHAR(32)  NOT NULL DEFAULT 'TEAM' COMMENT '团队类型：TEAM/DEPARTMENT/APPLICATION/PROJECT/CUSTOMER/COST_CENTER',
  `description`   VARCHAR(255) DEFAULT NULL COMMENT '描述',
  `status`        VARCHAR(32)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
  `created_by`    VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_team_code` (`team_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队表';

-- -------------------------------------------------------------
-- 2. tl_user 用户（员工 / 终端客户 / 机器人账号 / 服务 / 系统）
--    user_type: EMPLOYEE / END_CUSTOMER / BOT / SERVICE / SYSTEM
--    quota_mode: PERSONAL_ONLY / TEAM_ONLY / PERSONAL_FIRST_THEN_TEAM
--    role: USER / TEAM_ADMIN / ADMIN
--    username 全局唯一（登录账号）；user_code 在同一团队下唯一
--    login_enabled: 1 允许登录 / 0 禁止登录
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_user` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `team_code`     VARCHAR(64)  NOT NULL COMMENT '所属团队',
  `user_code`     VARCHAR(64)  NOT NULL COMMENT '用户编码（团队内唯一）',
  `user_name`     VARCHAR(128) NOT NULL COMMENT '用户名称',
  `user_type`     VARCHAR(32)  NOT NULL DEFAULT 'EMPLOYEE' COMMENT '类型：EMPLOYEE/END_CUSTOMER/BOT/SERVICE/SYSTEM',
  `quota_mode`    VARCHAR(32)  NOT NULL DEFAULT 'PERSONAL_FIRST_THEN_TEAM' COMMENT '个人额度模式：PERSONAL_ONLY/TEAM_ONLY/PERSONAL_FIRST_THEN_TEAM',
  `role`          VARCHAR(32)  NOT NULL DEFAULT 'USER' COMMENT '角色：USER/TEAM_ADMIN/ADMIN',
  `username`      VARCHAR(64)  DEFAULT NULL COMMENT '登录账号（全局唯一，NULL 表示不可登录）',
  `password_hash` VARCHAR(128) DEFAULT NULL COMMENT '登录密码哈希（bcrypt，前缀{bcrypt}；兼容旧 SHA-256，首次登录自动升级）',
  `login_enabled` TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否允许登录：1允许/0禁止',
  `last_login_at` DATETIME     DEFAULT NULL COMMENT '最后登录时间',
  `password_changed_at` DATETIME DEFAULT NULL COMMENT '密码修改时间（NULL 表示尚未修改，首次登录需强制改密）',
  `status`        VARCHAR(32)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_team_user` (`team_code`, `user_code`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_team` (`team_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- -------------------------------------------------------------
-- 3. tl_api_key API Key（强绑定 team/user）
--    status: ENABLED / DISABLED / EXPIRED / REVOKED
--    access_key 全局唯一（客户端调用凭证）；key_id 内部唯一
--    secret 明文仅创建/重置时返回一次（secret_hash 存储）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_api_key` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `team_code`     VARCHAR(64)  NOT NULL COMMENT '所属团队',
  `user_code`     VARCHAR(64)  NOT NULL COMMENT '绑定用户',
  `key_id`        VARCHAR(64)  NOT NULL COMMENT 'API Key 标识（内部唯一，如 key-xxxx）',
  `key_name`      VARCHAR(128) NOT NULL COMMENT 'Key 名称（便于识别用途）',
  `access_key`    VARCHAR(64)  NOT NULL COMMENT 'Access Key（客户端调用唯一凭证，格式 tl_ak_ + 32 位 base62）',
  `secret_hash`   VARCHAR(128) DEFAULT NULL COMMENT 'Secret 哈希（明文仅创建/重置时返回一次）',
  `allowed_models` VARCHAR(512) DEFAULT NULL COMMENT '允许的模型（逗号分隔，NULL 表示全部）',
  `status`        VARCHAR(32)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED/EXPIRED/REVOKED',
  `expire_at`     DATETIME     DEFAULT NULL COMMENT '过期时间（NULL 表示永不过期）',
  `last_used_at`  DATETIME     DEFAULT NULL COMMENT '最后使用时间',
  `created_by`    VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_access_key` (`access_key`),
  UNIQUE KEY `uk_key_id` (`key_id`),
  KEY `idx_team_user` (`team_code`, `user_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API Key 表';

-- -------------------------------------------------------------
-- 4. tl_quota_rule 配额规则（PRD V5.2 责任链拦截模型）
--    规则描述「谁(target_type+target_code) + 哪个模型(model) + 哪种额度(limit_type) + 限额(limit_value) + 周期(period)」
--    limit_type: TOKEN（token 数）/ COST（金额）/ REQUEST_COUNT（请求次数）
--    period: MINUTE / HOUR / DAY / WEEK / MONTH / YEAR / TOTAL
--    状态：ENABLED / DISABLED
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_quota_rule` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `target_type`     VARCHAR(32)   NOT NULL COMMENT '目标类型：TEAM/USER',
  `target_code`     VARCHAR(64)   NOT NULL COMMENT '目标编码（team_code/user_code）',
  `model`           VARCHAR(64)   DEFAULT NULL COMMENT '模型维度，NULL 或 * 表示全部模型',
  `limit_type`      VARCHAR(32)   NOT NULL COMMENT '限制类型：TOKEN/COST/REQUEST_COUNT',
  `limit_value`     DECIMAL(18,4) NOT NULL COMMENT '限制值（TOKEN/请求数为整数，COST 为金额）',
  `period`          VARCHAR(32)   NOT NULL COMMENT '周期：MINUTE/HOUR/DAY/WEEK/MONTH/YEAR/TOTAL',
  `status`          VARCHAR(32)   NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
  `description`     VARCHAR(255)  DEFAULT NULL COMMENT '描述',
  `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`, `target_code`, `model`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配额规则表';

-- -------------------------------------------------------------
-- 5. tl_provider_credential 供应商密钥凭证（PRD V5.0）
--    scope_type: GLOBAL / TEAM；真实 api_key 加密存储、永不回显
--    查找优先级：Team 专属 Credential → GLOBAL Credential → PROVIDER_NOT_FOUND
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_provider_credential` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `credential_code` VARCHAR(64)   NOT NULL COMMENT '凭证编码（全局唯一）',
  `provider`        VARCHAR(64)   NOT NULL COMMENT '供应商编码，如 openai/anthropic/deepseek',
  `provider_name`   VARCHAR(128)  DEFAULT NULL COMMENT '供应商名称',
  `credential_name` VARCHAR(128)  NOT NULL COMMENT '凭证名称',
  `scope_type`      VARCHAR(32)   NOT NULL COMMENT '作用域：GLOBAL/TEAM',
  `team_code`       VARCHAR(64)   DEFAULT NULL COMMENT '所属团队（scope_type=TEAM 必填）',
  `api_base_url`    VARCHAR(512)  DEFAULT NULL COMMENT '上游 Base URL（为空使用默认）',
  `api_key_enc`     VARCHAR(1024) NOT NULL COMMENT '上游 API Key（AES-GCM 加密存储）',
  `model`           VARCHAR(128)  DEFAULT NULL COMMENT '绑定模型（NULL 表示全部）',
  `status`          VARCHAR(32)   NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
  `remark`          VARCHAR(255)  DEFAULT NULL COMMENT '备注',
  `created_by`      VARCHAR(64)   DEFAULT NULL COMMENT '创建人',
  `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_credential_code` (`credential_code`),
  KEY `idx_scope_team` (`scope_type`, `team_code`),
  KEY `idx_provider` (`provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商密钥凭证表';

-- -------------------------------------------------------------
-- 5.1 tl_provider 供应商字典表（内置模板，PRD V5.0 §9.7）
--    内置主流大模型厂商的 OpenAI 兼容 Base URL，供控制台下拉选择、避免拼写错误；
--    is_builtin=1 为系统预置模板，可展示/只读；is_builtin=0 为自定义供应商。
--    openai_compatible: 是否兼容 OpenAI 协议可 HTTP 直接透传（0 表示需协议转换 Adapter）
--    requires_endpoint: 是否需要拼接 Endpoint ID（如火山方舟 /api/v3/ep-xxx）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_provider` (
  `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `provider_code`     VARCHAR(64)   NOT NULL COMMENT '供应商编码，如 openai/deepseek/qwen',
  `provider_name`     VARCHAR(128)  NOT NULL COMMENT '供应商名称',
  `base_url`          VARCHAR(512)  DEFAULT NULL COMMENT '默认 Base URL（OpenAI 兼容协议）',
  `is_builtin`        TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否内置模板：1内置/0自定义',
  `icon_url`          VARCHAR(255)  DEFAULT NULL COMMENT '图标地址（前端 Logo）',
  `openai_compatible` TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否 OpenAI 协议兼容可直传：1是/0否(需Adapter)',
  `requires_endpoint` TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否需拼接 Endpoint ID：1是/0否',
  `status`            VARCHAR(32)   NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
  `remark`            VARCHAR(255)  DEFAULT NULL COMMENT '备注',
  `created_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_code` (`provider_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商字典表';

-- 内置供应商模板（与代码枚举 LlmProvider 保持一致；Anthropic 原生 API 不兼容 OpenAI 协议，暂标记不可直传）
INSERT INTO `tl_provider`
  (`provider_code`, `provider_name`, `base_url`, `is_builtin`, `openai_compatible`, `requires_endpoint`, `remark`) VALUES
  ('openai',     'OpenAI',                 'https://api.openai.com/v1',                                  1, 1, 0, '行业标准'),
  ('deepseek',   'DeepSeek',               'https://api.deepseek.com/v1',                               1, 1, 0, '完全兼容'),
  ('qwen',       '阿里云百炼（通义）',     'https://dashscope.aliyuncs.com/compatible-mode/v1',           1, 1, 0, '注意 compatible-mode 路径'),
  ('moonshot',   '月之暗面（Kimi）',       'https://api.moonshot.cn/v1',                                 1, 1, 0, '完全兼容'),
  ('yi',         '零一万物（Yi）',         'https://api.lingyiwanwu.com/v1',                             1, 1, 0, '完全兼容'),
  ('baichuan',   '百川智能',               'https://api.baichuan-ai.com/v1',                            1, 1, 0, '完全兼容'),
  ('minimax',    'MiniMax',                'https://api.minimax.chat/v1',                               1, 1, 0, '完全兼容'),
  ('siliconflow','硅基流动',               'https://api.siliconflow.cn/v1',                             1, 1, 0, '开源模型聚合平台'),
  ('openrouter', 'OpenRouter',             'https://openrouter.ai/api/v1',                              1, 1, 0, '全球主流聚合平台'),
  ('zhipu',      '智谱 AI（GLM）',         'https://open.bigmodel.cn/api/paas/v4',                      1, 1, 0, '路径为 /v4 而非 /v1'),
  ('volcengine', '火山方舟（豆包）',       'https://ark.cn-beijing.volces.com/api/v3',                   1, 1, 1, '需拼接控制台创建的 Endpoint ID'),
  ('xai',        'xAI（Grok）',            'https://api.x.ai/v1',                                       1, 1, 0, '完全兼容'),
  ('gemini',     'Google Gemini',          'https://generativelanguage.googleapis.com/v1beta/openai',   1, 1, 0, 'OpenAI 兼容端点'),
  ('mistral',    'Mistral AI',             'https://api.mistral.ai/v1',                                 1, 1, 0, '完全兼容'),
  ('groq',       'Groq',                   'https://api.groq.com/openai/v1',                            1, 1, 0, '完全兼容'),
  ('stepfun',    '阶跃星辰',               'https://api.stepfun.com/v1',                                1, 1, 0, '完全兼容'),
  ('jina',       'Jina AI',                'https://api.jina.ai/v1',                                    1, 1, 0, '完全兼容'),
  ('ollama',     'Ollama',                 'http://localhost:11434/v1',                                 1, 1, 0, '本地部署'),
  ('anthropic',  'Anthropic（Claude）',    'https://api.anthropic.com/v1',                              1, 0, 0, '原生 API 不兼容 OpenAI 协议，需 Adapter');

-- -------------------------------------------------------------
-- 6. tl_team_model_policy 团队模型策略（PRD V5.0）
--    定义 Team 允许使用的模型及绑定的 Provider Credential；
--    启用策略时未命中模型将返回 MODEL_NOT_ALLOWED
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_team_model_policy` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `team_code`       VARCHAR(64)   NOT NULL COMMENT '团队编码',
  `model`           VARCHAR(128)  NOT NULL COMMENT '模型（''*'' 表示全部模型）',
  `credential_code` VARCHAR(64)   NOT NULL COMMENT '凭证编码（tl_provider_credential.credential_code）',
  `enabled`         TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否启用：1启用/0停用',
  `remark`          VARCHAR(255)  DEFAULT NULL COMMENT '备注',
  `created_by`      VARCHAR(64)   DEFAULT NULL COMMENT '创建人',
  `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_team_model` (`team_code`, `model`),
  KEY `idx_credential` (`credential_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队模型策略表';

-- -------------------------------------------------------------
-- 7. tl_usage_log Token 使用日志（PRD V5.0 / 计费快照 V5.3 / 缓存计费 V5.4）
--    estimated_*: jtokkit 预估（check 阶段与结算阶段）
--    usage_source: PROVIDER（厂商真实值）/ ESTIMATED（本地预估值）
--    status: SUCCESS / INTERRUPTED / ERROR / FAILED / CANCELLED
--    anomaly_detected: 预估值与真实值偏差超过阈值标记 1
--    计费快照（Billing Snapshot，核心不可变字段）：写入后费用永久固化，
--    后续修改价格/汇率只影响新调用；报表必须 SUM(cost) 不得动态重算
--    缓存计费（V5.4）：cached_tokens（OpenAI cached_tokens / DeepSeek
--    prompt_cache_hit_tokens / Anthropic cache_read_input_tokens）按缓存读取
--    单价计费，cache_write_tokens（Anthropic cache_creation_input_tokens）按
--    缓存写入单价计费；未配置缓存单价时按正常输入价兜底；单价同样固化为快照
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_usage_log` (
  `id`                        BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `trace_id`                  VARCHAR(64)   NOT NULL COMMENT '追踪ID（check 时生成，report 时闭环）',
  `team_code`                 VARCHAR(64)   NOT NULL COMMENT '团队',
  `user_code`                 VARCHAR(64)   DEFAULT NULL COMMENT '用户',
  `api_key_id`                VARCHAR(64)   DEFAULT NULL COMMENT 'API Key 标识（tl_api_key.key_id）',
  `model`                     VARCHAR(64)   NOT NULL COMMENT '模型',
  `provider`                  VARCHAR(64)   DEFAULT NULL COMMENT '模型供应商，如 openai/deepseek/qwen',
  `estimated_prompt_tokens`   BIGINT        NOT NULL DEFAULT 0 COMMENT '预估输入 token（jtokkit）',
  `estimated_completion_tokens` BIGINT      NOT NULL DEFAULT 0 COMMENT '预估输出 token（jtokkit）',
  `estimated_total_tokens`    BIGINT        NOT NULL DEFAULT 0 COMMENT '预估总 token',
  `prompt_tokens`             BIGINT        NOT NULL DEFAULT 0 COMMENT '供应商真实输入 token',
  `completion_tokens`         BIGINT        NOT NULL DEFAULT 0 COMMENT '供应商真实输出 token',
  `total_tokens`              BIGINT        NOT NULL DEFAULT 0 COMMENT '供应商真实总 token',
  `currency`                  VARCHAR(16)   NOT NULL DEFAULT 'CNY' COMMENT '计费快照：模型原始计价币种（USD/CNY）',
  `input_price_snapshot`      DECIMAL(18,10) NOT NULL DEFAULT 0 COMMENT '计费快照：调用时输入单价（每 Token）',
  `output_price_snapshot`     DECIMAL(18,10) NOT NULL DEFAULT 0 COMMENT '计费快照：调用时输出单价（每 Token）',
  `exchange_rate_snapshot`    DECIMAL(18,6) NOT NULL DEFAULT 1 COMMENT '计费快照：调用时汇率（原始币种→本位币）',
  `base_currency`             VARCHAR(16)   NOT NULL DEFAULT 'CNY' COMMENT '计费快照：企业本位币',
  `cost_original`             DECIMAL(18,6) NOT NULL DEFAULT 0 COMMENT '计费快照：原始币种费用（如 USD）',
  `cost`                      DECIMAL(18,6) NOT NULL DEFAULT 0 COMMENT '计费快照：本位币费用（如 CNY，核心扣费/报表字段）',
  `cached_tokens`             BIGINT        NOT NULL DEFAULT 0 COMMENT '缓存命中 token（OpenAI cached_tokens / DeepSeek prompt_cache_hit_tokens / Anthropic cache_read_input_tokens）',
  `cache_write_tokens`        BIGINT        NOT NULL DEFAULT 0 COMMENT '缓存写入 token（Anthropic cache_creation_input_tokens）',
  `cache_read_price_snapshot` DECIMAL(18,10) DEFAULT NULL COMMENT '计费快照：调用时缓存读取单价（每 Token，未配置为 NULL）',
  `cache_write_price_snapshot` DECIMAL(18,10) DEFAULT NULL COMMENT '计费快照：调用时缓存写入单价（每 Token，未配置为 NULL）',
  `consume_from`              VARCHAR(32)   NOT NULL DEFAULT 'TEAM' COMMENT '额度消耗来源：TEAM/USER',
  `usage_source`              VARCHAR(32)   NOT NULL DEFAULT 'PROVIDER' COMMENT '记录来源：PROVIDER/ESTIMATED',
  `status`                    VARCHAR(32)   NOT NULL DEFAULT 'SUCCESS' COMMENT '状态：SUCCESS/INTERRUPTED/ERROR/FAILED/CANCELLED',
  `anomaly_detected`          TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '异常标记：1 预估值与真实值偏差超阈值',
  `anomaly_detail`            VARCHAR(1024) DEFAULT NULL COMMENT '异常详情',
  `created_at`                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trace_id` (`trace_id`),
  KEY `idx_api_key_created` (`api_key_id`, `created_at`),
  KEY `idx_team_created` (`team_code`, `created_at`),
  KEY `idx_model_created` (`model`, `created_at`),
  KEY `idx_user` (`team_code`, `user_code`),
  KEY `idx_anomaly` (`anomaly_detected`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Token 使用日志表';

-- -------------------------------------------------------------
-- 8. tl_audit_log 审计日志
--    event_type: LOGIN_SUCCESS / LOGIN_FAILED / CREATE_TEAM / UPDATE_TEAM /
--                CREATE_USER / DISABLE_USER / RESET_PASSWORD / CREATE_API_KEY /
--                DISABLE_API_KEY / DELETE_API_KEY / UPDATE_USER_QUOTA /
--                UPDATE_TEAM_QUOTA / QUOTA_BLOCK / USAGE_ANOMALY /
--                CREDENTIAL_CREATE / CREDENTIAL_UPDATE / CREDENTIAL_DELETE / CREDENTIAL_DISABLE
--    result: SUCCESS / FAILED
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_audit_log` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `team_code`      VARCHAR(64)   DEFAULT NULL COMMENT '团队',
  `user_code`      VARCHAR(64)   DEFAULT NULL COMMENT '用户',
  `api_key_id`     VARCHAR(64)   DEFAULT NULL COMMENT 'API Key 标识',
  `operator`       VARCHAR(64)   DEFAULT NULL COMMENT '操作人（登录账号）',
  `event_type`     VARCHAR(32)   NOT NULL COMMENT '事件类型：LOGIN_SUCCESS/LOGIN_FAILED/CREATE_TEAM/CREATE_USER/DISABLE_USER/RESET_PASSWORD/CREATE_API_KEY/DISABLE_API_KEY/DELETE_API_KEY/UPDATE_USER_QUOTA/UPDATE_TEAM_QUOTA/QUOTA_BLOCK/USAGE_ANOMALY',
  `target_type`    VARCHAR(32)   DEFAULT NULL COMMENT '目标类型：TEAM/USER/API_KEY/QUOTA_RULE/CREDENTIAL',
  `target_code`    VARCHAR(64)   DEFAULT NULL COMMENT '目标编码',
  `detail`         VARCHAR(1024) DEFAULT NULL COMMENT '详情（JSON）',
  `result`         VARCHAR(32)   DEFAULT 'SUCCESS' COMMENT '结果：SUCCESS/FAILED',
  `trace_id`       VARCHAR(64)   DEFAULT NULL COMMENT '关联 trace_id（拦截事件）',
  `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_event_created` (`event_type`, `created_at`),
  KEY `idx_target` (`team_code`, `target_type`, `target_code`),
  KEY `idx_operator` (`operator`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表';

-- -------------------------------------------------------------
-- 9. tl_setting 系统设置（键值对，PRD V5.0）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_setting` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `setting_key` VARCHAR(64)  NOT NULL COMMENT '配置键',
  `setting_value` VARCHAR(512) DEFAULT NULL COMMENT '配置值',
  `description` VARCHAR(255) DEFAULT NULL COMMENT '描述',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_setting_key` (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统设置表';

-- 默认系统设置（V5：check 上下文 TTL / 异常偏差阈值等）
INSERT INTO `tl_setting` (`setting_key`, `setting_value`, `description`) VALUES
  ('system_name',     'TokenLimit Console',      '系统名称'),
  ('gateway_url',     'http://localhost:8080',   '网关公网地址（客户端 Base URL）'),
  ('default_model',   'gpt-4o-mini',             '默认模型'),
  ('alert_threshold', '80',                      '预算告警阈值（%）'),
  ('anomaly_deviation_threshold', '0.5',         '异常计费检测偏差阈值（预估与真实值偏差比例）'),
  ('check_context_ttl_seconds', '3600',          'check/report 上下文缓存时间（秒）'),
  ('audit_retention', '90',                      '审计日志保留时间（天）'),
  ('notify_channel',  'dingtalk',                '告警通知渠道'),
  ('login_fail_limit','5',                       '登录失败锁定阈值（次）'),
  ('login_fail_lock_minutes','30',               '登录失败锁定时长（分钟）'),
  ('base_currency',   'CNY',                     '企业本位币（财务报表与 cost 统一换算到该币种）'),
  ('usd_to_cny_rate', '7.2',                     '汇率：USD→CNY（计费快照时固化到 usage_log，修改只影响新调用）');

-- -------------------------------------------------------------
-- 10. tl_model_price 模型价格表（模型列表与价格基准）
--     价格单位：每 1 个 Token 的单价（避免计算时频繁除以 1000000）
--     计费公式：cost = prompt_tokens × input_price_per_token + completion_tokens × output_price_per_token
--     缓存计费（V5.4）：输入成本 = (prompt - cached - write) × 输入价 + cached × 缓存读取价 + write × 缓存写入价；
--     未配置缓存单价时，缓存 token 按正常输入价计费（等价无折扣）
--     修改价格只影响新调用（usage_log 已固化为计费快照，历史费用不可变）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_model_price` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `provider`      VARCHAR(64)   NOT NULL COMMENT '供应商编码，如 openai/anthropic/deepseek',
  `model`         VARCHAR(128)  NOT NULL COMMENT '模型名称',
  `input_price_per_token`   DECIMAL(18,10) NOT NULL DEFAULT 0 COMMENT '输入单价（每 Token）',
  `output_price_per_token`  DECIMAL(18,10) NOT NULL DEFAULT 0 COMMENT '输出单价（每 Token）',
  `cache_read_price_per_token`  DECIMAL(18,10) DEFAULT NULL COMMENT '缓存读取单价（如 Anthropic Prompt Caching，预留）',
  `cache_write_price_per_token` DECIMAL(18,10) DEFAULT NULL COMMENT '缓存写入单价（预留）',
  `currency`      VARCHAR(16)   NOT NULL DEFAULT 'CNY' COMMENT '币种：USD/CNY',
  `status`        VARCHAR(32)   NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
  `effective_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生效时间（记录最近一次改价时间，价格即改即生效）',
  `created_by`    VARCHAR(64)   DEFAULT NULL COMMENT '创建人',
  `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_model` (`provider`, `model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型价格表';

-- 模型价格示例（每 Token 单价，参考 2024 底/2025 初官方定价，上线前需核对最新价格）
-- 换算：每百万 Tokens 价格 ÷ 1,000,000；缓存价按厂商折扣（OpenAI 5 折、Anthropic 读 1 折/写 1.25 倍、DeepSeek 1 折）
INSERT INTO `tl_model_price` (`provider`, `model`, `input_price_per_token`, `output_price_per_token`, `cache_read_price_per_token`, `cache_write_price_per_token`, `currency`) VALUES
  -- DeepSeek（CNY/百万：chat 1.00/2.00，缓存读取 1 折 0.10；reasoner 4.00/16.00，缓存读取 0.40）
  ('deepseek',   'deepseek-chat',      0.0000010000, 0.0000020000, 0.0000001000, NULL, 'CNY'),
  ('deepseek',   'deepseek-reasoner',  0.0000040000, 0.0000160000, 0.0000004000, NULL, 'CNY'),
  -- OpenAI（USD/百万：gpt-4o 2.50/10.00，缓存读取 5 折 1.25；gpt-4o-mini 0.15/0.60，缓存读取 0.075）
  ('openai',     'gpt-4o',             0.0000025000, 0.0000100000, 0.0000012500, NULL, 'USD'),
  ('openai',     'gpt-4o-mini',        0.0000001500, 0.0000006000, 0.0000000750, NULL, 'USD'),
  -- Anthropic（USD/百万：claude-3-5-sonnet 3.00/15.00，缓存读取 1 折 0.30，缓存写入 1.25 倍 3.75）
  ('anthropic',  'claude-3-5-sonnet',  0.0000030000, 0.0000150000, 0.0000003000, 0.0000037500, 'USD'),
  -- 阿里云百炼（CNY/百万：qwen-max 20.00/60.00，qwen-turbo 2.00/6.00）
  ('qwen',       'qwen-max',           0.0000200000, 0.0000600000, NULL, NULL, 'CNY'),
  ('qwen',       'qwen-turbo',         0.0000020000, 0.0000060000, NULL, NULL, 'CNY'),
  -- 智谱 GLM-4-Flash（免费）
  ('zhipu',      'glm-4-flash',       0.0000000000, 0.0000000000, NULL, NULL, 'CNY');

-- =============================================================
-- 测试数据
-- =============================================================

-- 团队
INSERT INTO `tl_team` (`team_code`, `team_name`, `team_type`, `description`, `created_by`) VALUES
  ('team-rd', '研发中心', 'DEPARTMENT', '产品研发', 'superadmin'),
  ('team-cs', '客户服务', 'DEPARTMENT', '智能客服', 'superadmin'),
  ('app-code-assistant', '代码助手', 'APPLICATION', '面向研发的代码助手', 'superadmin');

-- 用户（初始账号密码均为 TokenLimit@123，首次登录需强制改密）
-- 密码哈希: 下方预置为旧版 SHA-256("TokenLimit@123")，首次登录校验通过后自动升级为 bcrypt
INSERT INTO `tl_user`
  (`team_code`, `user_code`, `user_name`, `user_type`, `quota_mode`, `role`, `username`, `password_hash`, `login_enabled`) VALUES
  ('team-rd', 'user-superadmin', '超级管理员', 'SYSTEM', 'TEAM_ONLY', 'ADMIN',
   'superadmin', '78c7f9ab9dca3e59b0531cf668653e44338a67c3d2a73ab6817bb786adb292bf', 1),
  ('team-rd', 'user-zhangsan', '张三', 'EMPLOYEE', 'PERSONAL_FIRST_THEN_TEAM', 'USER',
   'zhangsan', '78c7f9ab9dca3e59b0531cf668653e44338a67c3d2a73ab6817bb786adb292bf', 1),
  ('team-cs', 'user-lisi',     '李四', 'EMPLOYEE', 'PERSONAL_ONLY', 'USER',
   'lisi', '78c7f9ab9dca3e59b0531cf668653e44338a67c3d2a73ab6817bb786adb292bf', 1),
  ('team-rd', 'bot-codereview', '代码评审机器人', 'BOT', 'TEAM_ONLY', 'USER',
   NULL, NULL, 0);

-- API Key（secret 仅演示用，生产请通过管理端创建获取随机 secret）
INSERT INTO `tl_api_key`
  (`team_code`, `user_code`, `key_id`, `key_name`, `access_key`, `secret_hash`, `status`, `created_by`) VALUES
  ('team-rd', 'user-zhangsan', 'key-zhangsan-demo', '张三-演示Key', 'tl_ak_zhangsan_demo',
   'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'ENABLED', 'superadmin'),
  ('team-cs', 'user-lisi', 'key-lisi-demo', '李四-演示Key', 'tl_ak_lisi_demo',
   'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'ENABLED', 'superadmin');

-- 配额规则示例（PRD V5.0：TOKEN/COST/REQUEST_COUNT + DAY/MONTH/TOTAL）
INSERT INTO `tl_quota_rule`
  (`target_type`, `target_code`, `model`, `limit_type`, `limit_value`, `period`, `status`, `description`) VALUES
  ('TEAM', 'team-rd',        NULL, 'TOKEN', 2000000, 'DAY',   'ENABLED', '研发中心每日 Token 上限'),
  ('TEAM', 'team-rd',        NULL, 'COST',  100.0000, 'DAY',   'ENABLED', '研发中心每日费用上限'),
  ('USER', 'user-zhangsan',  NULL, 'TOKEN', 100000,  'DAY',   'ENABLED', '张三每日个人 Token 额度'),
  ('TEAM', 'app-code-assistant', NULL, 'REQUEST_COUNT', 5000, 'DAY', 'ENABLED', '代码助手每日请求次数上限');

-- -------------------------------------------------------------
-- 11. tl_vendor_bill 供应商账单表（V4 遗留，V5 不做供应商账单自动同步）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_vendor_bill` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `bill_date`       DATE          NOT NULL COMMENT '账单日期',
  `provider`        VARCHAR(64)   NOT NULL COMMENT '供应商编码',
  `model`           VARCHAR(128)  NOT NULL COMMENT '模型名称',
  `provider_tokens` BIGINT        NOT NULL DEFAULT 0 COMMENT '供应商统计 Tokens',
  `provider_cost`   DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '供应商计费金额',
  `currency`        VARCHAR(16)   NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `status`          VARCHAR(32)   NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
  `remark`          VARCHAR(255)  DEFAULT NULL COMMENT '备注',
  `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_provider_model` (`bill_date`, `provider`, `model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商账单表';

-- -------------------------------------------------------------
-- 12. tl_reconcile_task 对账任务表（V4 遗留，V5 不做对账引擎）
--    status: PENDING / RUNNING / COMPLETED / FAILED
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_reconcile_task` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_code`     VARCHAR(64)   NOT NULL COMMENT '对账任务编码',
  `bill_date`     DATE          NOT NULL COMMENT '对账账单日期',
  `provider`      VARCHAR(64)   NOT NULL COMMENT '供应商编码',
  `status`        VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/RUNNING/COMPLETED/FAILED',
  `total_items`   INT           NOT NULL DEFAULT 0 COMMENT '明细总数',
  `diff_items`    INT           NOT NULL DEFAULT 0 COMMENT '差异明细数',
  `dispute_items` INT           NOT NULL DEFAULT 0 COMMENT '争议明细数',
  `avg_diff_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '平均差异率',
  `executed_at`   DATETIME      DEFAULT NULL COMMENT '执行时间',
  `remark`        VARCHAR(255)  DEFAULT NULL COMMENT '备注',
  `created_by`    VARCHAR(64)   DEFAULT NULL COMMENT '创建人',
  `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_code` (`task_code`),
  KEY `idx_date_provider` (`bill_date`, `provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对账任务表';

-- -------------------------------------------------------------
-- 13. tl_reconcile_item 对账明细表（V4 遗留）
--    status: CONSISTENT(一致) / DIFFERENCE(差异) / PENDING(待处理) / DISPUTED(争议)
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_reconcile_item` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id`         BIGINT        NOT NULL COMMENT '对账任务ID',
  `bill_date`       DATE          NOT NULL COMMENT '账单日期',
  `provider`        VARCHAR(64)   NOT NULL COMMENT '供应商编码',
  `model`           VARCHAR(128)  NOT NULL COMMENT '模型名称',
  `team_code`       VARCHAR(64)   DEFAULT NULL COMMENT '团队编码（可空，模型维度对账）',
  `our_tokens`      BIGINT        NOT NULL DEFAULT 0 COMMENT '我方 Tokens',
  `provider_tokens` BIGINT        NOT NULL DEFAULT 0 COMMENT '供应商 Tokens',
  `token_diff`      BIGINT        NOT NULL DEFAULT 0 COMMENT 'Tokens 差异（供应商-我方）',
  `token_diff_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT 'Tokens 差异率',
  `our_cost`        DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '我方成本',
  `provider_cost`   DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '供应商成本',
  `cost_diff`       DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '成本差异',
  `cost_diff_rate`  DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '成本差异率',
  `status`          VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT '状态：CONSISTENT/DIFFERENCE/PENDING/DISPUTED',
  `remark`          VARCHAR(255)  DEFAULT NULL COMMENT '备注',
  `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_date_provider` (`bill_date`, `provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对账明细表';

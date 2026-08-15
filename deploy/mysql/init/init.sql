-- =============================================================
-- Token Limit 数据库初始化脚本（PRD V4.0）
-- 数据库：tokenlimit
-- 字符集：utf8mb4
-- 模型：团队(Team) -> 用户(User) + API Key（已废除 Namespace）
-- 配额层级：仅 TEAM / USER
-- 角色：ADMIN / TEAM_ADMIN / USER
-- 网关：Provider 凭证(GLOBAL/TEAM) + 团队模型策略决定转发目标
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
  `status`        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/INACTIVE',
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
  `status`        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/INACTIVE',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_team_user` (`team_code`, `user_code`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_team` (`team_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- -------------------------------------------------------------
-- 3. tl_api_key API Key（强绑定 team/user）
--    status: ACTIVE / INACTIVE / EXPIRED / REVOKED
--    access_key 全局唯一（客户端调用凭证）；key_id 内部唯一
--    secret 明文仅创建/重置时返回一次（secret_hash 存储）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_api_key` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `team_code`     VARCHAR(64)  NOT NULL COMMENT '所属团队',
  `user_code`     VARCHAR(64)  NOT NULL COMMENT '绑定用户',
  `key_id`        VARCHAR(64)  NOT NULL COMMENT 'API Key 标识（内部唯一，如 key-xxxx）',
  `key_name`      VARCHAR(128) NOT NULL COMMENT 'Key 名称（便于识别用途）',
  `access_key`    VARCHAR(64)  NOT NULL COMMENT 'Access Key（客户端调用唯一凭证，格式 tl_ak_xxx）',
  `secret_hash`   VARCHAR(128) DEFAULT NULL COMMENT 'Secret 哈希（明文仅创建/重置时返回一次）',
  `status`        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/INACTIVE/EXPIRED/REVOKED',
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
-- 4. tl_quota_rule 配额规则
--    MVP 阶段 target_type 仅支持 TEAM / USER
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_quota_rule` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `rule_code`       VARCHAR(64)   NOT NULL COMMENT '规则编码',
  `target_type`     VARCHAR(32)   NOT NULL COMMENT '目标类型：TEAM/USER',
  `target_code`     VARCHAR(64)   NOT NULL COMMENT '目标编码（team_code/user_code）',
  `model`           VARCHAR(64)   DEFAULT NULL COMMENT '模型维度，NULL 表示全部模型',
  `limit_type`      VARCHAR(32)   NOT NULL COMMENT '限制类型：TOKEN/COST/REQUEST_COUNT/RPM/TPM',
  `limit_value`     DECIMAL(18,4) NOT NULL COMMENT '限制值（TOKEN/请求数为整数，COST 为金额）',
  `period`          VARCHAR(32)   NOT NULL COMMENT '周期：MINUTE/HOUR/DAY/MONTH/TOTAL',
  `priority`        INT           NOT NULL DEFAULT 0 COMMENT '优先级，数字越小越优先（对象维度较精确者优先）',
  `enabled`         TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否启用：1启用/0停用',
  `description`     VARCHAR(255)  DEFAULT NULL COMMENT '描述',
  `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_code` (`rule_code`),
  KEY `idx_target` (`target_type`, `target_code`, `model`),
  KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配额规则表';

-- -------------------------------------------------------------
-- 5. tl_provider_credential 供应商密钥凭证（PRD V4.0）
--    scope_type: GLOBAL / TEAM；真实 api_key 加密存储、永不回显
--    status: ACTIVE / INACTIVE
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
  `status`          VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/INACTIVE',
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
-- 6. tl_team_model_policy 团队模型策略（PRD V4.0）
--    查找优先级：Team 专属凭证 → GLOBAL 凭证 → PROVIDER_NOT_FOUND
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
-- 7. tl_usage_log Token 使用日志
--    consume_from: PERSONAL / TEAM
--    status: SUCCESS / FAILED / CANCELLED / PENDING
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_usage_log` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `trace_id`          VARCHAR(64)  NOT NULL COMMENT '追踪ID（check 时生成，report 时闭环）',
  `team_code`         VARCHAR(64)  NOT NULL COMMENT '团队',
  `user_code`         VARCHAR(64)  DEFAULT NULL COMMENT '用户',
  `api_key_id`        VARCHAR(64)  DEFAULT NULL COMMENT 'API Key 标识',
  `model`             VARCHAR(64)  NOT NULL COMMENT '模型',
  `provider`          VARCHAR(64)  DEFAULT NULL COMMENT '模型供应商，如 OPENAI/DEEPSEEK/QWEN',
  `estimated_tokens`  BIGINT       NOT NULL DEFAULT 0 COMMENT '预估 token（check 预扣减量）',
  `prompt_tokens`     BIGINT       NOT NULL DEFAULT 0 COMMENT '输入 token',
  `completion_tokens` BIGINT       NOT NULL DEFAULT 0 COMMENT '输出 token',
  `total_tokens`      BIGINT       NOT NULL DEFAULT 0 COMMENT '实际总 token',
  `cost`              DECIMAL(18,6) NOT NULL DEFAULT 0 COMMENT '费用（元）',
  `consume_from`      VARCHAR(32)  NOT NULL DEFAULT 'TEAM' COMMENT '抵扣来源：PERSONAL/TEAM',
  `status`            VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/SUCCESS/FAILED/CANCELLED',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trace_id` (`trace_id`),
  KEY `idx_api_key_created` (`api_key_id`, `created_at`),
  KEY `idx_team_created` (`team_code`, `created_at`),
  KEY `idx_model_created` (`model`, `created_at`),
  KEY `idx_user` (`team_code`, `user_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Token 使用日志表';

-- -------------------------------------------------------------
-- 8. tl_audit_log 审计日志
--    event_type: LOGIN_SUCCESS / LOGIN_FAILED / CREATE_TEAM /
--                CREATE_USER / DISABLE_USER / RESET_PASSWORD / CREATE_API_KEY /
--                DISABLE_API_KEY / DELETE_API_KEY / UPDATE_USER_QUOTA / UPDATE_TEAM_QUOTA / QUOTA_BLOCK
--    result: SUCCESS / FAILED
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_audit_log` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `team_code`      VARCHAR(64)   DEFAULT NULL COMMENT '团队',
  `user_code`      VARCHAR(64)   DEFAULT NULL COMMENT '用户',
  `api_key_id`     VARCHAR(64)   DEFAULT NULL COMMENT 'API Key 标识',
  `operator`       VARCHAR(64)   DEFAULT NULL COMMENT '操作人（登录账号）',
  `event_type`     VARCHAR(32)   NOT NULL COMMENT '事件类型：LOGIN_SUCCESS/LOGIN_FAILED/CREATE_TEAM/CREATE_USER/DISABLE_USER/RESET_PASSWORD/CREATE_API_KEY/DISABLE_API_KEY/DELETE_API_KEY/UPDATE_USER_QUOTA/UPDATE_TEAM_QUOTA/QUOTA_BLOCK',
  `target_type`    VARCHAR(32)   DEFAULT NULL COMMENT '目标类型：TEAM/USER/API_KEY/QUOTA_RULE',
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
-- 9. tl_setting 系统设置（键值对）
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

-- 默认系统设置
INSERT INTO `tl_setting` (`setting_key`, `setting_value`, `description`) VALUES
  ('system_name',     'TokenLimit Console',  '系统名称'),
  ('gateway_url',     'http://localhost:8080', '网关公网地址（客户端 Base URL）'),
  ('default_model',   'gpt-4o-mini',         '默认模型'),
  ('estimated_buffer','1.1',                 '预估安全系数（预估 token 乘以该系数）'),
  ('trace_timeout_minutes','10',             'Trace 预占过期时间（分钟）'),
  ('unsettled_policy','ESTIMATE',            '未结算策略：ESTIMATE 按预估结算 / RELEASE 释放冻结'),
  ('fail_policy',     'FAIL_CLOSE',          '上游异常策略：FAIL_CLOSE / FAIL_OPEN'),
  ('alert_threshold', '80',                  '预算告警阈值（%）'),
  ('allow_overdraft_percent','3',            '允许透支比例（%）'),
  ('audit_retention', '90',                  '审计日志保留时间（天）'),
  ('notify_channel',  'dingtalk',            '告警通知渠道'),
  ('login_fail_limit','5',                   '登录失败锁定阈值（次）'),
  ('login_fail_lock_minutes','30',           '登录失败锁定时长（分钟）');

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
   'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'ACTIVE', 'superadmin'),
  ('team-cs', 'user-lisi', 'key-lisi-demo', '李四-演示Key', 'tl_ak_lisi_demo',
   'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'ACTIVE', 'superadmin');

-- 配额规则示例
-- Team / team-rd 每日 Token 上限 2000000
INSERT INTO `tl_quota_rule`
  (`rule_code`, `target_type`, `target_code`, `model`, `limit_type`, `limit_value`, `period`, `priority`) VALUES
  ('rule-team-rd-day-token',       'TEAM', 'team-rd', NULL, 'TOKEN', 2000000, 'DAY', 20),
  ('rule-team-rd-day-cost',        'TEAM', 'team-rd', NULL, 'COST',  100.0000, 'DAY', 20),
  ('rule-user-zhangsan-day-token', 'USER', 'user-zhangsan', NULL, 'TOKEN', 100000, 'DAY', 5),
  ('rule-team-rd-min-rpm',         'TEAM', 'team-rd', NULL, 'RPM', 60, 'MINUTE', 20);

-- -------------------------------------------------------------
-- 10. tl_model_price 模型价格表（对账中心 - 价格管理，PRD Phase 4）
--    价格单位：元 / 百万 tokens
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_model_price` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `provider`      VARCHAR(64)   NOT NULL COMMENT '供应商编码，如 openai/anthropic/deepseek',
  `model`         VARCHAR(128)  NOT NULL COMMENT '模型名称',
  `input_price`   DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '输入单价（元/百万token）',
  `output_price`  DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '输出单价（元/百万token）',
  `currency`      VARCHAR(16)   NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `status`        VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/INACTIVE',
  `created_by`    VARCHAR(64)   DEFAULT NULL COMMENT '创建人',
  `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_model` (`provider`, `model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型价格表';

-- 模型价格示例（元/百万token）
INSERT INTO `tl_model_price` (`provider`, `model`, `input_price`, `output_price`) VALUES
  ('openai',     'gpt-4o',          15.000000, 60.000000),
  ('openai',     'gpt-4o-mini',      0.150000,  0.600000),
  ('anthropic',  'claude-3-5-sonnet',20.000000, 100.000000),
  ('deepseek',   'deepseek-chat',    1.000000,  2.000000);

-- -------------------------------------------------------------
-- 11. tl_vendor_bill 供应商账单表（对账中心 - 账单导入）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_vendor_bill` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `bill_date`       DATE          NOT NULL COMMENT '账单日期',
  `provider`        VARCHAR(64)   NOT NULL COMMENT '供应商编码',
  `model`           VARCHAR(128)  NOT NULL COMMENT '模型名称',
  `provider_tokens` BIGINT        NOT NULL DEFAULT 0 COMMENT '供应商统计 Tokens',
  `provider_cost`   DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '供应商计费金额',
  `currency`        VARCHAR(16)   NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `status`          VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/INACTIVE',
  `remark`          VARCHAR(255)  DEFAULT NULL COMMENT '备注',
  `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_provider_model` (`bill_date`, `provider`, `model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商账单表';

-- -------------------------------------------------------------
-- 12. tl_reconcile_task 对账任务表
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
-- 13. tl_reconcile_item 对账明细表
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

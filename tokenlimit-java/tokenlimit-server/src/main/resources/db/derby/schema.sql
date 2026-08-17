-- =============================================================
-- Token Limit Derby 数据库初始化脚本（参考 Nacos 单机版方案）
-- 纯 Java 嵌入式数据库，零配置启动
-- =============================================================

-- -------------------------------------------------------------
-- 1. tl_team 团队表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tl_team (
  id            BIGINT       NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
  team_code     VARCHAR(64)  NOT NULL,
  team_name     VARCHAR(128) NOT NULL,
  team_type     VARCHAR(32)  NOT NULL DEFAULT 'TEAM',
  description   VARCHAR(255) DEFAULT NULL,
  status        VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
  created_by    VARCHAR(64)  DEFAULT NULL,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_team_code UNIQUE (team_code)
);

-- -------------------------------------------------------------
-- 2. tl_user 用户表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tl_user (
  id            BIGINT       NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
  team_code     VARCHAR(64)  NOT NULL,
  user_code     VARCHAR(64)  NOT NULL,
  user_name     VARCHAR(128) NOT NULL,
  user_type     VARCHAR(32)  NOT NULL DEFAULT 'EMPLOYEE',
  role          VARCHAR(32)  NOT NULL DEFAULT 'USER',
  quota_mode    VARCHAR(32)  NOT NULL DEFAULT 'TEAM_ONLY',
  personal_quota DECIMAL(10,6) DEFAULT 0.00,
  personal_used DECIMAL(10,6) DEFAULT 0.00,
  login_enabled SMALLINT     NOT NULL DEFAULT 1,
  status        VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
  created_by    VARCHAR(64)  DEFAULT NULL,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_user_code_team UNIQUE (team_code, user_code)
);

-- -------------------------------------------------------------
-- 3. tl_provider 供应商表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tl_provider (
  id            BIGINT       NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
  provider_code VARCHAR(64)  NOT NULL,
  provider_name VARCHAR(128) NOT NULL,
  base_url      VARCHAR(255) DEFAULT NULL,
  description   VARCHAR(255) DEFAULT NULL,
  status        VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_provider_code UNIQUE (provider_code)
);

-- -------------------------------------------------------------
-- 4. tl_provider_credential 供应商凭证表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tl_provider_credential (
  id              BIGINT       NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
  provider_code   VARCHAR(64)  NOT NULL,
  credential_name VARCHAR(128) NOT NULL,
  credential_type VARCHAR(32)  NOT NULL DEFAULT 'API_KEY',
  api_key         VARCHAR(512) NOT NULL,
  api_base_url    VARCHAR(255) DEFAULT NULL,
  scope_type      VARCHAR(32)  NOT NULL DEFAULT 'GLOBAL',
  scope_value     VARCHAR(64)  DEFAULT NULL,
  status          VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
  created_by      VARCHAR(64)  DEFAULT NULL,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

-- -------------------------------------------------------------
-- 5. tl_api_key API Key 表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tl_api_key (
  id            BIGINT       NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
  key_hash      VARCHAR(128) NOT NULL,
  key_prefix    VARCHAR(16)  NOT NULL,
  team_code     VARCHAR(64)  NOT NULL,
  user_code     VARCHAR(64)  DEFAULT NULL,
  quota_mode    VARCHAR(32)  NOT NULL DEFAULT 'TEAM_ONLY',
  status        VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
  expires_at    TIMESTAMP    DEFAULT NULL,
  last_used_at  TIMESTAMP    DEFAULT NULL,
  created_by    VARCHAR(64)  DEFAULT NULL,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_key_hash UNIQUE (key_hash)
);

-- -------------------------------------------------------------
-- 6. tl_model_price 模型价格表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tl_model_price (
  id              BIGINT       NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
  model_name      VARCHAR(128) NOT NULL,
  provider_code   VARCHAR(64)  NOT NULL,
  input_price     DECIMAL(10,6) NOT NULL DEFAULT 0.000000,
  output_price    DECIMAL(10,6) NOT NULL DEFAULT 0.000000,
  currency        VARCHAR(8)   NOT NULL DEFAULT 'USD',
  unit            VARCHAR(16)  NOT NULL DEFAULT '1K_TOKENS',
  peak_price_multiplier DECIMAL(3,2) DEFAULT 1.00,
  off_peak_price_multiplier DECIMAL(3,2) DEFAULT 1.00,
  peak_hours      VARCHAR(64)  DEFAULT NULL,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_model_provider UNIQUE (model_name, provider_code)
);

-- -------------------------------------------------------------
-- 7. tl_usage_log 用量日志表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tl_usage_log (
  id                BIGINT       NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
  request_id        VARCHAR(64)  NOT NULL,
  api_key_hash      VARCHAR(128) NOT NULL,
  team_code         VARCHAR(64)  NOT NULL,
  user_code         VARCHAR(64)  DEFAULT NULL,
  model_name        VARCHAR(128) NOT NULL,
  provider_code     VARCHAR(64)  NOT NULL,
  input_tokens      INT          NOT NULL DEFAULT 0,
  output_tokens     INT          NOT NULL DEFAULT 0,
  total_tokens      INT          NOT NULL DEFAULT 0,
  estimated_input   INT          DEFAULT NULL,
  estimated_output  INT          DEFAULT NULL,
  estimated_total   INT          DEFAULT NULL,
  cost              DECIMAL(10,6) NOT NULL DEFAULT 0.000000,
  estimated_cost    DECIMAL(10,6) DEFAULT NULL,
  anomaly_detected  SMALLINT     NOT NULL DEFAULT 0,
  deviation_ratio   DECIMAL(5,2) DEFAULT NULL,
  status            VARCHAR(32)  NOT NULL DEFAULT 'COMPLETED',
  error_message     VARCHAR(512) DEFAULT NULL,
  is_peak_hour      SMALLINT     DEFAULT 0,
  created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

-- -------------------------------------------------------------
-- 8. tl_audit_log 审计日志表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tl_audit_log (
  id            BIGINT       NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
  operator      VARCHAR(64)  NOT NULL,
  operation     VARCHAR(64)  NOT NULL,
  resource_type VARCHAR(32)  NOT NULL,
  resource_id   VARCHAR(64)  DEFAULT NULL,
  action        VARCHAR(64)  NOT NULL,
  request_uri   VARCHAR(255) DEFAULT NULL,
  method        VARCHAR(16)  DEFAULT NULL,
  ip_address    VARCHAR(45)  DEFAULT NULL,
  user_agent    VARCHAR(255) DEFAULT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS',
  error_message VARCHAR(512) DEFAULT NULL,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

-- -------------------------------------------------------------
-- 9. tl_team_member 团队成员表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tl_team_member (
  id            BIGINT       NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
  team_code     VARCHAR(64)  NOT NULL,
  user_code     VARCHAR(64)  NOT NULL,
  role          VARCHAR(32)  NOT NULL DEFAULT 'MEMBER',
  joined_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_team_member UNIQUE (team_code, user_code)
);

-- -------------------------------------------------------------
-- 10. tl_team_model_strategy 团队模型策略表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tl_team_model_strategy (
  id            BIGINT       NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
  team_code     VARCHAR(64)  NOT NULL,
  model_name    VARCHAR(128) NOT NULL,
  allowed       SMALLINT     NOT NULL DEFAULT 1,
  quota_limit   DECIMAL(10,6) DEFAULT NULL,
  priority      INT          NOT NULL DEFAULT 0,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_team_model UNIQUE (team_code, model_name)
);

-- -------------------------------------------------------------
-- 初始数据
-- -------------------------------------------------------------
INSERT INTO tl_provider (provider_code, provider_name, base_url, description) VALUES
('openai', 'OpenAI', 'https://api.openai.com/v1', 'OpenAI GPT 系列模型'),
('dashscope', '阿里云百炼', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '阿里云通义千问系列模型'),
('deepseek', '深度求索', 'https://api.deepseek.com/v1', 'DeepSeek 系列模型');

INSERT INTO tl_team (team_code, team_name, team_type, description) VALUES
('default', '默认团队', 'TEAM', '系统默认团队');

INSERT INTO tl_user (team_code, user_code, user_name, user_type, role, quota_mode, login_enabled) VALUES
('default', 'admin', '系统管理员', 'EMPLOYEE', 'ADMIN', 'TEAM_ONLY', 1);

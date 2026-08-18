-- =============================================================
-- TokenLimit V6.0 预算体系重构迁移脚本
-- 变更内容：
-- 1. 废除年度预算，确立滚动月度预算机制
-- 2. 新增 tl_api_key_policy 表存储用户自定义策略（日限额、小时限额、单次请求限额）
-- 3. 移除 tl_team 和 tl_user 中的年度相关字段（如有）
-- =============================================================

USE `tokenlimit`;

-- -------------------------------------------------------------
-- 1. 新增 tl_api_key_policy 表（V6.0 核心特性：User 自助风控）
--    存储 API Key 级别的细粒度限额策略，由 End User 自主设置
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tl_api_key_policy` (
  `id`                        BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `access_key`                VARCHAR(64)   NOT NULL COMMENT 'API Key access_key（关联 tl_api_key）',
  `team_code`                 VARCHAR(64)   NOT NULL COMMENT '所属团队编码',
  `user_code`                 VARCHAR(64)   NOT NULL COMMENT '绑定用户编码',
  `key_id`                    VARCHAR(64)   NOT NULL COMMENT 'API Key 标识',
  
  -- 单次请求限额（防异常大请求）
  `max_tokens_per_request`    BIGINT        DEFAULT NULL COMMENT '单次请求最大 token 数（NULL 表示不限制）',
  
  -- 小时级限额（小时熔断）
  `hourly_limit`              BIGINT        DEFAULT NULL COMMENT '小时限额（token 数，NULL 表示不限制）',
  `hourly_used`               BIGINT        NOT NULL DEFAULT 0 COMMENT '小时已用量（Redis 同步值）',
  `hourly_reset_at`           DATETIME      DEFAULT NULL COMMENT '小时限额重置时间',
  
  -- 日级限额
  `daily_limit`               BIGINT        DEFAULT NULL COMMENT '日限额（token 数，NULL 表示不限制）',
  `daily_used`                BIGINT        NOT NULL DEFAULT 0 COMMENT '日已用量（Redis 同步值）',
  `daily_reset_at`            DATETIME      DEFAULT NULL COMMENT '日限额重置时间',
  
  -- 状态控制
  `is_frozen`                 TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否冻结：1 冻结/0 正常（用户手动或系统自动）',
  `frozen_reason`             VARCHAR(255)  DEFAULT NULL COMMENT '冻结原因',
  
  `status`                    VARCHAR(32)   NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
  `created_at`                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_access_key` (`access_key`),
  KEY `idx_team_user` (`team_code`, `user_code`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API Key 限额策略表（V6.0 新增，User 自助风控）';

-- -------------------------------------------------------------
-- 2. 清理 tl_quota_rule 中的年度周期数据
--    V6.0 只保留月度及更细粒度周期
-- -------------------------------------------------------------
UPDATE `tl_quota_rule` SET status = 'DISABLED' WHERE period = 'YEAR';

-- -------------------------------------------------------------
-- 3. 将现有 TOTAL 周期的 Team/User 规则转换为 MONTH 周期（滚动月度预算）
--    注：实际业务中可能需要更复杂的转换逻辑，此处仅做示例
-- -------------------------------------------------------------
-- 注释掉，避免误操作现有数据
-- UPDATE `tl_quota_rule` SET period = 'MONTH' WHERE target_type = 'TEAM' AND period = 'TOTAL';
-- UPDATE `tl_quota_rule` SET period = 'MONTH' WHERE target_type = 'USER' AND period = 'TOTAL';

-- -------------------------------------------------------------
-- 4. 初始化 tl_api_key_policy 数据（从现有 tl_api_key 同步）
-- -------------------------------------------------------------
INSERT INTO `tl_api_key_policy` (`access_key`, `team_code`, `user_code`, `key_id`, `status`)
SELECT `access_key`, `team_code`, `user_code`, `key_id`, `status`
FROM `tl_api_key`
ON DUPLICATE KEY UPDATE `updated_at` = CURRENT_TIMESTAMP;

-- -------------------------------------------------------------
-- 5. 添加 tl_quota_rule 表的 period 字段约束注释
--    V6.0 支持的周期：MINUTE / HOUR / DAY / WEEK / MONTH
-- -------------------------------------------------------------
-- 注意：YEAR 周期已废弃，TOTAL 周期仅用于特殊场景


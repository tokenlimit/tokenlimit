-- TokenLimit V5.5 峰谷定价策略数据库迁移脚本
-- 执行时间：2024-01-XX
-- 说明：为 tl_model_price 表添加峰谷定价字段，为 tl_usage_log 添加价格系数快照字段

-- ============================================
-- 1. 为 tl_model_price 表添加峰谷定价字段
-- ============================================

ALTER TABLE tl_model_price 
ADD COLUMN pricing_type VARCHAR(32) NOT NULL DEFAULT 'FLAT' COMMENT '定价类型：FLAT(固定定价), PEAK_OFF_PEAK(峰谷定价)' AFTER status,
ADD COLUMN peak_multiplier DECIMAL(5,2) NOT NULL DEFAULT 1.00 COMMENT '高峰时段价格系数 (如 1.0)' AFTER pricing_type,
ADD COLUMN off_peak_multiplier DECIMAL(5,2) NOT NULL DEFAULT 0.50 COMMENT '低谷时段价格系数 (如 0.50 表示 5 折)' AFTER peak_multiplier,
ADD COLUMN off_peak_start TIME DEFAULT NULL COMMENT '低谷开始时间 (如 22:00:00)' AFTER off_peak_multiplier,
ADD COLUMN off_peak_end TIME DEFAULT NULL COMMENT '低谷结束时间 (如 08:00:00，支持跨天)' AFTER off_peak_start;

-- 为新增字段添加索引（可选，提升查询性能）
CREATE INDEX idx_pricing_type ON tl_model_price(pricing_type);

-- ============================================
-- 2. 为 tl_usage_log 表添加价格系数快照字段
-- ============================================

ALTER TABLE tl_usage_log
ADD COLUMN price_multiplier_snapshot DECIMAL(5,2) DEFAULT 1.00 COMMENT '调用时的峰谷价格系数（V5.5 峰谷定价策略）' AFTER cache_write_price_snapshot;

-- ============================================
-- 3. 初始化现有数据（将现有记录设为固定定价）
-- ============================================

UPDATE tl_model_price 
SET pricing_type = 'FLAT',
    peak_multiplier = 1.00,
    off_peak_multiplier = 1.00
WHERE pricing_type IS NULL OR pricing_type = '';

-- ============================================
-- 4. 示例数据：为热门模型配置峰谷定价策略
-- ============================================

-- 示例 1: OpenAI gpt-4o 配置峰谷定价（22:00-08:00 低谷 5 折）
UPDATE tl_model_price 
SET pricing_type = 'PEAK_OFF_PEAK',
    peak_multiplier = 1.00,
    off_peak_multiplier = 0.50,
    off_peak_start = '22:00:00',
    off_peak_end = '08:00:00'
WHERE provider = 'openai' AND model = 'gpt-4o';

-- 示例 2: 通义千问 qwen-plus 配置峰谷定价（23:00-07:00 低谷 6 折）
UPDATE tl_model_price 
SET pricing_type = 'PEAK_OFF_PEAK',
    peak_multiplier = 1.00,
    off_peak_multiplier = 0.60,
    off_peak_start = '23:00:00',
    off_peak_end = '07:00:00'
WHERE provider = 'qwen' AND model = 'qwen-plus';

-- ============================================
-- 5. 验证迁移结果
-- ============================================

-- 查看峰谷定价配置
SELECT provider, model, pricing_type, peak_multiplier, off_peak_multiplier, 
       off_peak_start, off_peak_end, currency, status
FROM tl_model_price
WHERE pricing_type = 'PEAK_OFF_PEAK';

-- 查看价格系数快照字段
SELECT trace_id, model, cost_base, price_multiplier_snapshot
FROM tl_usage_log
WHERE price_multiplier_snapshot IS NOT NULL
LIMIT 10;

-- ============================================
-- 迁移完成提示
-- ============================================
-- ✅ tl_model_price 表已添加峰谷定价字段
-- ✅ tl_usage_log 表已添加价格系数快照字段
-- ✅ 现有数据已初始化为固定定价模式
-- ✅ 示例数据已配置峰谷定价策略
-- 
-- 注意事项：
-- 1. 修改价格或峰谷规则只影响新调用，历史账单不会变更
-- 2. 低谷时段支持跨天配置（如 22:00-次日 08:00）
-- 3. 缓存折扣与峰谷折扣可叠加（相乘）
-- 4. 建议在业务低峰期执行此迁移脚本
-- ============================================

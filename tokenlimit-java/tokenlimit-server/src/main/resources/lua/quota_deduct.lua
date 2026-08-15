-- =============================================================
-- 配额扣减 Lua 脚本
--
-- 输入：
--   KEYS[1]  = 配额使用量 key
--   ARGV[1]  = 本次扣减量（estimated tokens）
--   ARGV[2]  = 配额上限（limit value）
--   ARGV[3]  = key 过期时间（秒）
--
-- 返回：
--   1 = 扣减成功（未超限）
--   0 = 超限拒绝
--   2 = 上限配置异常（limit <= 0）
-- =============================================================

local used = tonumber(redis.call('GET', KEYS[1]) or '0')
local delta = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])

if limit == nil or limit <= 0 then
    return 2
end

if used + delta > limit then
    return 0
end

redis.call('INCRBY', KEYS[1], delta)

if redis.call('EXISTS', KEYS[1]) == 1 and redis.call('TTL', KEYS[1]) < 0 then
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
end

return 1

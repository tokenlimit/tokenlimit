-- =============================================================
-- 配额扣减回补/追扣 Lua 脚本
--
-- 用于 report 阶段修正预扣减与实际的差异。
--   diff > 0 表示实际大于预估，需追加扣减
--   diff < 0 表示实际小于预估，需回补
--
-- 输入：
--   KEYS[1] = 配额使用量 key
--   ARGV[1] = 修正量（可正可负）
--   ARGV[2] = 配额上限
--   ARGV[3] = key 过期时间（秒）
--
-- 返回：
--   当前已使用量；若追加后超限返回 -1
-- =============================================================

local used = tonumber(redis.call('GET', KEYS[1]) or '0')
local diff = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])

if diff > 0 and used + diff > limit then
    return -1
end

redis.call('INCRBY', KEYS[1], diff)

if redis.call('TTL', KEYS[1]) < 0 then
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
end

return used + diff

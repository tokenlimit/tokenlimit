-- =============================================================
-- Quota pre-deduct script (check phase, PREDUCT mode)
--
-- Dual-key design:
--   used key = real consumption of finished calls (consistent with MySQL aggregation)
--   pre  key = pre-deducted total of in-flight requests (estimated tokens)
-- Rule: used + pre + delta > limit -> reject (remaining < 0 after pre-deduct)
--
-- Input:
--   KEYS[1] = used key
--   KEYS[2] = pre key
--   ARGV[1] = pre-deduct amount (estimated tokens; 1 for REQUEST_COUNT rule)
--   ARGV[2] = quota limit (limit value)
--   ARGV[3] = key TTL in seconds (remaining period; set on first creation)
--
-- Return:
--   1 = pre-deduct ok
--   0 = rejected (over limit, nothing deducted)
--   2 = bad config (limit <= 0)
-- =============================================================

local used = tonumber(redis.call('GET', KEYS[1]) or '0')
local pre = tonumber(redis.call('GET', KEYS[2]) or '0')
local delta = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])

if limit == nil or limit <= 0 then
    return 2
end

if used + pre + delta > limit then
    return 0
end

redis.call('INCRBY', KEYS[2], delta)

if redis.call('TTL', KEYS[2]) < 0 then
    redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))
end

return 1

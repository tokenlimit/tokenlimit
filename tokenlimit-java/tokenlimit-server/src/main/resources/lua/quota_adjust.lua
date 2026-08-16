-- =============================================================
-- Quota settle script (report phase, PREDUCT mode)
--
-- Roll back pre-deduct + accumulate real usage:
--   pre  key -= rollback amount (same as the pre-deducted amount at check)
--   used key += actual amount (real tokens from provider / 1 call count)
--
-- Input:
--   KEYS[1] = used key
--   KEYS[2] = pre key
--   ARGV[1] = rollback amount (>= 0; pre -= rollback, delete key when <= 0)
--   ARGV[2] = actual amount  (>= 0; used += actual, skip when 0 for pure rollback)
--   ARGV[3] = used key TTL in seconds (remaining period; set on first creation)
--
-- Return: current used value
-- =============================================================

local rollback = tonumber(ARGV[1])
if rollback ~= nil and rollback > 0 then
    local pre = tonumber(redis.call('GET', KEYS[2]) or '0')
    if pre - rollback <= 0 then
        redis.call('DEL', KEYS[2])
    else
        redis.call('DECRBY', KEYS[2], rollback)
    end
end

local actual = tonumber(ARGV[2])
if actual ~= nil and actual > 0 then
    local used = redis.call('INCRBY', KEYS[1], actual)
    if used == actual then
        redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
    end
    return used
end

return tonumber(redis.call('GET', KEYS[1]) or '0')

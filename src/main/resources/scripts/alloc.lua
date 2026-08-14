local dedupKey = KEYS[1]
local zsetKey = KEYS[2]
local totalKey = KEYS[3]
local dirtySetKey = KEYS[4]

local participantId = ARGV[2]
local delta = tonumber(ARGV[3])
local cap = tonumber(ARGV[4])
local itemId = ARGV[5]

if redis.call('EXISTS', dedupKey) == 1 then
    return -1
end

local myQty = tonumber(redis.call('ZSCORE', zsetKey, participantId) or "0")
if delta < 0 and (myQty + delta) < 0 then
    return -2
end

local total = tonumber(redis.call('GET', totalKey) or "0")
if delta > 0 and (total + delta) > cap then
    return -3
end

redis.call('ZINCRBY', zsetKey, delta, participantId)
redis.call('INCRBY', totalKey, delta)
redis.call('SET', dedupKey, 1, 'EX', 86400)
return 1
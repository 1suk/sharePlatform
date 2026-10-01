local claimed = {}
local limit = tonumber(ARGV[1])
local now = tonumber(ARGV[2])
for i = 1, limit do
    local itemId = redis.call('SPOP', KEYS[1])
    if not itemId then break end
    if redis.call('ZADD', KEYS[2], 'NX', now, itemId) == 1 then
        table.insert(claimed, itemId)
    else
        redis.call('SADD', KEYS[1], itemId)
    end
end
return claimed

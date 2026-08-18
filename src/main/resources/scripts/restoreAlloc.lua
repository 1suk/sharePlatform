local tempKey = KEYS[1]
local zsetKey = KEYS[2]
local totalKey = KEYS[3]
local total = ARGV[1]

if redis.call('EXISTS', tempKey) == 1 then
    redis.call('RENAME', tempKey, zsetKey)
    redis.call('SET', totalKey, total)
    return 1
end

return 0
local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
if score and tonumber(score) == tonumber(ARGV[2]) then
    return redis.call('ZREM', KEYS[1], ARGV[1])
end
return 0

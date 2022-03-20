--- 判断当前线程标识，与锁的标识是否一致
if (redis.call('get',KEYS[1]) == ARGV[1]) then
    -- 释放锁
    return redis.call('del',KEYS[1])
end
return 0
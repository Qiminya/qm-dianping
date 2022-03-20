package com.atqm.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(Long timeoutSec) {
        String key = KEY_PREFIX + name;

            // 获取线程表示id
        String threadId = ID_PREFIX+Thread.currentThread().getId();

            // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, threadId, timeoutSec, TimeUnit.SECONDS);
            // 返回获取锁的结果
        return success;
    }

    @Override
    public void unlock() {
        String key = KEY_PREFIX + name;

        // 获取线程表示id
        String threadId = ID_PREFIX+Thread.currentThread().getId();
                // 判断是否是当前线程的锁
        String id = stringRedisTemplate.opsForValue().get(key);

        // 当前的锁是自己线程的才进行释放（删除）
        if(threadId.equals(id)){
            stringRedisTemplate.delete(key);
        }
    }
}

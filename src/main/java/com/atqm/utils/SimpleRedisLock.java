package com.atqm.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(Long timeoutSec) {
            String key = KEY_PREFIX + name;

            // 获取线程表示id
            long threadId = Thread.currentThread().getId();

            // 获取锁
            Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, threadId + "", timeoutSec, TimeUnit.SECONDS);
            // 返回获取锁的结果
            return success;
    }

    @Override
    public void unlock() {
            String key = KEY_PREFIX + name;

            stringRedisTemplate.delete(key);
    }
}

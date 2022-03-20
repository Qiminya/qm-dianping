package com.atqm.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    // 初始化lua脚本
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.TYPE);
    }



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

        // 当前的锁是自己线程的才进行释放（删除）
//        if(threadId.equals(id)){
//            stringRedisTemplate.delete(key);
//        }

        // 使用Lua脚本实现多个操作原子性
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(key),
                Thread.currentThread().getId());
    }
}

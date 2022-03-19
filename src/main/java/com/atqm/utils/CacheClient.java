package com.atqm.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.atqm.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.atqm.utils.RedisConstants.CACHE_NULL_TTL;
import static com.atqm.utils.RedisConstants.CACHE_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

//    方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public  void set(String key,Object object, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(object),time,unit);
    }
//    方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicalExpire(String key,Object object, Long time, TimeUnit unit){
        // 设置逻辑时间
        RedisData redisData = new RedisData();
        redisData.setData(object);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData),time,unit);
}
//    方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    public <T,R> T queryWithMutex(String keyPrefix, R id, Class<T> type, Function<R, T> function,Long time,TimeUnit unit)  {
        String key = keyPrefix + id;
    // 1.从redis查商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
    // 2.判断是否存在
        if(StrUtil.isNotBlank(json)){
        // 存在 直接返回商铺
            return JSONUtil.toBean(json, type);
        }
    // 是否为空值
        if(json != null){
        // 返回一个错误信息
        return  null;
    }
        // 3.不存在，根据id查数据库
        T t = function.apply(id);

        // 4.不存在返回错误
        if(t == null){
            //将空值写入redis缓存
            stringRedisTemplate.opsForValue().set(key,"",time, unit);

            return null;
        }
        // 5.存在，写入redis，注入缓存
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(t));

        return t;
}
//    方法4：根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
}

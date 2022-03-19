package com.atqm.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.atqm.dto.Result;
import com.atqm.entity.Shop;
import com.atqm.mapper.ShopMapper;
import com.atqm.service.IShopService;
import com.atqm.utils.RedisConstants;
import com.atqm.utils.RedisData;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.atqm.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
//        Shop shop = queryWithMutex(id);
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null){
            return Result.fail("店铺不存在！");
        }
        // 返回
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id)  {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            // 存在 直接返回商铺
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson != null){
            // 返回一个错误信息
            return  null;
        }

        // 实现缓存重建

        // 获取互斥锁
        String lockKey= "lock:shop:"+id;

        boolean isLock = tyrLock(lockKey);


        Shop shop = null;
        try {
        // 判断是否获取成功
        if(!isLock){
            // 失败休眠再重试
            Thread.sleep(50);
            return queryWithMutex(id);
        }

        // 3.不存在，根据id查数据库
        shop = getById(id);
        Thread.sleep(200);
        // 4.不存在返回错误
        if(shop == null){
            //将空值写入redis缓存
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }
        // 5.存在，写入redis，注入缓存
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));

        } catch (InterruptedException e) {
            e.printStackTrace();
        }finally {
            // 释放互斥锁
            unlock(lockKey);
        }

        return shop;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    public Shop queryWithLogicalExpire(Long id)  {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isBlank(shopJson)){
            // 3.未命中返回空
            return null;
        }
        // 4.命中，把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);

        // 取出店铺对象信息
        Object data = redisData.getData();
        Shop shop = JSONUtil.toBean((JSONObject) data, Shop.class);
        // 取出逻辑过期时间
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            // 5.1未过期，直接返回店铺信息
            return shop;
        }
        // 5.2已过期 缓存重建

        // 6.缓存重建
        // 6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        boolean isLock = tyrLock(lockKey);
        // 6.2判断互斥锁是否获取成功
        if(isLock){
            // 6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    // 重建缓存
                    this.saveShop2Redis(id,30l);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4失败，直接返回过期店铺信息
        return shop;
    }

    private boolean tyrLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSeconds){
        // 查询店铺数据
        Shop shop = getById(id);
        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空！");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);

        return Result.ok();
    }
}

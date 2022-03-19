package com.atqm.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.atqm.dto.Result;
import com.atqm.entity.Shop;
import com.atqm.mapper.ShopMapper;
import com.atqm.service.IShopService;
import com.atqm.utils.RedisConstants;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.atqm.utils.RedisConstants.CACHE_NULL_TTL;
import static com.atqm.utils.RedisConstants.CACHE_SHOP_KEY;

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
        Shop shop = queryWithMutex(id);

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

    private boolean tyrLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
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

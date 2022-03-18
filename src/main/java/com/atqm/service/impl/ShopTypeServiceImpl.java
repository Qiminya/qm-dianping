package com.atqm.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.atqm.dto.Result;
import com.atqm.entity.ShopType;
import com.atqm.mapper.ShopTypeMapper;
import com.atqm.service.IShopTypeService;
import com.atqm.utils.RedisConstants;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key = RedisConstants.SHOP_TYPE_KEY;
        // 在redis查询分类
        // 看redis李是否存了分类信息(大小)
        Long size = stringRedisTemplate.opsForList().size(key);

        //redis缓存中有数据
        if(size != 0){
            List<ShopType> list = new ArrayList<ShopType>();
            for(long i = 0l;i < size; i++){
                ShopType shopType = JSONUtil.toBean(stringRedisTemplate.opsForList().index(key, i), ShopType.class);
                list.add(shopType);
            }
            System.out.println("利用redis缓存----缓存命中");
            return Result.ok(list);
        }
        List<String> str = stringRedisTemplate.opsForList().range(key, 0l, -1l);

        // 无，则在数据库查
        // 查数据库
        List<ShopType> typeList = query().list();

        // 分类缓存到redis
        for(ShopType shopType : typeList){
            stringRedisTemplate.opsForList().rightPush(key, JSONUtil.toJsonStr(shopType));
        }

        System.out.println("数据库得到分类信息");
        return Result.ok(typeList);
    }
}

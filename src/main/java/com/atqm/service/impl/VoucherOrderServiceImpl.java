package com.atqm.service.impl;

import com.atqm.dto.Result;
import com.atqm.dto.UserDTO;
import com.atqm.entity.SeckillVoucher;
import com.atqm.entity.VoucherOrder;
import com.atqm.mapper.VoucherOrderMapper;
import com.atqm.service.ISeckillVoucherService;
import com.atqm.service.IVoucherOrderService;
import com.atqm.utils.RedisIdWorker;
import com.atqm.utils.SimpleRedisLock;
import com.atqm.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    // 初始化lua脚本
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.TYPE);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result seckillVoucher(Long voucherId) {
        // 用户id
        Long userId = UserHolder.getUser().getId();

        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );

        int flag = result.intValue();
        // 2.判断是否为0即成功
        if(flag != 0){
            // 2.1 不等于0，代表没有资格
            return Result.fail(flag == 1 ? "库存不足":"不能重复下单");
        }
        // 2.1为0代表可以购买,把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");

        return Result.ok(orderId);
    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        // 2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀还没开始！");
//        }
//        // 3.秒杀已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束！");
//        }
//        // 4.判断库存是否有
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足！");
//        }
//        return createVoucherOrder(voucherId);
//    }


//    @Resource
//    private RedissonClient redissonClient;
//
//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//
//        // 一人一单
//        Long userId = UserHolder.getUser().getId();
//
//        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
//
//        // 尝试获取锁
//        boolean isLock = redisLock.tryLock();
//        // 判断获取锁结果
//        if(!isLock){
//            // 获取锁失败，直接返回失败,或重试
//            return Result.fail("不允许重复下单");
//        }
//
//        try {
//            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//
//            // 判断是否买过了
//            if (count > 0) {
//                return Result.fail("您已购买过了！");
//            }
//
//            // 5.减少库存
//            // CAS 对待解决超卖问题
//            boolean success = seckillVoucherService
//                    .update()
//                    .setSql("stock = stock - 1")
//                    .eq("voucher_id", voucherId)
//                    .gt("stock", 0)
//                    .update();
//
//            if (!success) {
//                return Result.fail("库存不足！");
//            }
//
//            // 6.创建订单
//            VoucherOrder voucherOrder = new VoucherOrder();
//            // 6.1订单id
//            long orderId = redisIdWorker.nextId("order");
//            voucherOrder.setId(orderId);
//            // 6.2 用户id
//
//            voucherOrder.setUserId(userId);
//            // 6.3代金券id
//            voucherOrder.setVoucherId(voucherId);
//
//            //保存订单
//            save(voucherOrder);
//
//            // 7.返回订单id
//            return Result.ok(orderId);
//        }  finally {
//            redisLock.unlock();
//        }
//    }


//    @Resource
//    private StringRedisTemplate stringRedisTemplate;
//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//
//        // 一人一单
//        Long userId = UserHolder.getUser().getId();
//
//        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:", stringRedisTemplate);
//
//        // 尝试获取锁
//        boolean isLock = simpleRedisLock.tryLock(1200L);
//        // 判断获取锁结果
//        if(!isLock){
//            // 获取锁失败，直接返回失败,或重试
//            return Result.fail("不允许重复下单");
//        }
//
//        try {
//            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//
//            // 判断是否买过了
//            if (count > 0) {
//                return Result.fail("您已购买过了！");
//            }
//
//            // 5.减少库存
//            // CAS 对待解决超卖问题
//            boolean success = seckillVoucherService
//                    .update()
//                    .setSql("stock = stock - 1")
//                    .eq("voucher_id", voucherId)
//                    .gt("stock", 0)
//                    .update();
//
//            if (!success) {
//                return Result.fail("库存不足！");
//            }
//
//            // 6.创建订单
//            VoucherOrder voucherOrder = new VoucherOrder();
//            // 6.1订单id
//            long orderId = redisIdWorker.nextId("order");
//            voucherOrder.setId(orderId);
//            // 6.2 用户id
//
//            voucherOrder.setUserId(userId);
//            // 6.3代金券id
//            voucherOrder.setVoucherId(voucherId);
//
//            //保存订单
//            save(voucherOrder);
//
//            // 7.返回订单id
//            return Result.ok(orderId);
//        }  finally {
//            simpleRedisLock.unlock();
//        }
//    }

//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//
//        // 一人一单
//        Long userId = UserHolder.getUser().getId();
//
//        //userId.toString().intern(),字符串值一样返回对象就一样
//        synchronized (userId.toString().intern()) {
//
//            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//
//            // 判断是否买过了
//            if (count > 0) {
//                return Result.fail("您已购买过了！");
//            }
//
//            // 5.减少库存
//            // CAS 对待解决超卖问题
//            boolean success = seckillVoucherService
//                    .update()
//                    .setSql("stock = stock - 1")
//                    .eq("voucher_id", voucherId)
//                    .gt("stock", 0)
//                    .update();
//
//            if (!success) {
//                return Result.fail("库存不足！");
//            }
//
//            // 6.创建订单
//            VoucherOrder voucherOrder = new VoucherOrder();
//            // 6.1订单id
//            long orderId = redisIdWorker.nextId("order");
//            voucherOrder.setId(orderId);
//            // 6.2 用户id
//
//            voucherOrder.setUserId(userId);
//            // 6.3代金券id
//            voucherOrder.setVoucherId(voucherId);
//
//            //保存订单
//            save(voucherOrder);
//
//            // 7.返回订单id
//            return Result.ok(orderId);
//        }
//    }
}

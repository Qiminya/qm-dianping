package com.atqm.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.atqm.dto.Result;
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
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
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

//    // 阻塞队列
//    private BlockingQueue<VoucherOrder> ordersBlockingQueue=new ArrayBlockingQueue<>(1204*1024);

    // 线程池（单线程的）
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct//类初始化后就执行该方法(初始化方法)
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            String queueName = "stream.orders";
            while(true){
                try {
                    // 1.获取消息队列(redis-stream)里的订单信息XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 streams S1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2l)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if(list == null || list.isEmpty()){
                        // 如果为null，说明现在没信息，继续下一次循环
                        continue;
                    }
                    // 解析消息，封装为Order对象
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    // 3.创建订单
                    createVoucherOrder(voucherOrder);

                    // 4.确认消息XACK s1 g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常！",e);
                    // 处理pending里的消息
                    handlePendingList();
                }
            }
        }

        /**
         * 处理pending里的消息
         */
        private void handlePendingList() {
            String queueName = "stream.orders";
            while(true){
                try {
                    // 1.获取消息队列(redis-stream)里的订单信息XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 streams S1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2l)),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if(list == null || list.isEmpty()){
                        // 如果为null，说明pending队列没有待处理消息，直接结束循环
                        break;
                    }
                    // pending队列有待处理消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    // 3.创建订单
                    createVoucherOrder(voucherOrder);

                    // 4.确认消息XACK s1 g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理pending订单异常！",e);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                    // 下次循环，继续处理
                    continue;
                }
            }
        }
    }


//    private class VoucherOrderHandler implements Runnable{
//
//        @Override
//        public void run() {
//            // 1.获取队列里的订单信息
//            VoucherOrder voucherOrder = null;
//            try {
//                voucherOrder = ordersBlockingQueue.take();
//                System.out.println("有任务了，进行处理"+voucherOrder);
//                // 2.创建订单
//                createVoucherOrder(voucherOrder);
//            } catch (InterruptedException e) {
//                log.error("处理订单异常！",e);
//            }
//        }
//    }

    private void createVoucherOrder(VoucherOrder voucherOrder) {

        Long voucherId = voucherOrder.getVoucherId();

        Long userId =voucherOrder.getUserId();


        // 一人一单
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);

        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断获取锁结果
        if(!isLock){
            // 获取锁失败，直接返回失败,或重试
            log.error("不允许重复下单");
            return;
        }

        try {
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();

            // 判断是否买过了
            if (count > 0) {
                log.error("您已购买过了！");
                return;
            }

            // 5.减少库存
            // CAS 对待解决超卖问题
            boolean success = seckillVoucherService
                    .update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0)
                    .update();

            if (!success) {
                log.error("库存不足！");
                return;
            }
            //保存订单
            save(voucherOrder);

            // 7.返回订单id
            return;
        }  finally {
            redisLock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 用户id
        Long userId = UserHolder.getUser().getId();

        // 生成订单id
        Long orderId = redisIdWorker.nextId("order");

        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderId.toString()
        );

        int flag = result.intValue();
        // 2.判断是否为0即成功
        if(flag != 0){
            // 2.1 不等于0，代表没有资格
            return Result.fail(flag == 1 ? "库存不足":"不能重复下单");
        }

        // 3. 返回订单id
        return Result.ok(orderId);
    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 用户id
//        Long userId = UserHolder.getUser().getId();
//
//        // 1.执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString()
//        );
//
//        int flag = result.intValue();
//        // 2.判断是否为0即成功
//        if(flag != 0){
//            // 2.1 不等于0，代表没有资格
//            return Result.fail(flag == 1 ? "库存不足":"不能重复下单");
//        }
//        // 2.0为0代表可以购买,把下单信息保存到阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 2.1订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 2.2.用户id
//        voucherOrder.setUserId(userId);
//        // 2.3.代金券id
//        voucherOrder.setVoucherId(voucherId);
//
//        // 2.4 放入阻塞队列
//        ordersBlockingQueue.add(voucherOrder);
//        System.out.println("放入阻塞队列！！");
//        // 3. 返回订单id
//        return Result.ok(orderId);
//    }


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

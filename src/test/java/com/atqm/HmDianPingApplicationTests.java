package com.atqm;

import com.atqm.service.impl.ShopServiceImpl;
import com.atqm.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private ShopServiceImpl shopService;

    private ExecutorService es = Executors.newFixedThreadPool(300);

    @Test
    void redisDataTest (){
        shopService.saveShop2Redis(1l,10l);
    }

    @Test
    void RedisIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () ->{
            for(int i = 0;i < 100;i++){
                long id = redisIdWorker.nextId("order");
                System.out.println("id="+id);
            }
            latch.countDown();
        };

        long start = System.currentTimeMillis();

        for(int i=0;i<300;i++){
            es.submit(task);
        }
        latch.await();

        long end = System.currentTimeMillis();

        System.out.println("消耗时间time="+(end-start));

    }
}

---1.参数列表
---1.1.优惠券id
local voucherId = ARGV[1]

---1.2.用户id
local userId = ARGV[2]

---1.3.订单id
local orderId = ARGV[3]

---2.数据key
---2.1.库存key
local stockKey = 'seckill:stock:'.. voucherId
---2.2.订单key
local orderKey = 'seckill:order:'.. voucherId

---3.脚本业务
---3.1.判断库存是否有 get stockKey
if(tonumber(redis.call('get', stockKey)) <= 0)then
    ---3.2.库存不足,返回1
    return 1
end

---3.2.判断用户是否下单（set集合里是用户id和优惠券id，有用户id就说明用户已买）
if(redis.call('sismember',orderKey,userId) == 1)then
    ---3.3.存在说明已下单
    return 2
end

---3.4.减少库存incrby stockKey  -1
redis.call('incrby',stockKey,-1)

---3.5.下单（保存用户）sadd orderKey userId
redis.call('sadd',orderKey,userId)

---3.6.发消息到队列中XADD stream.orders * k1 v1 ,k2 v2
redis.call('xadd','stream.orders','*','userId', tostring(userId),'voucherId', tostring(voucherId),'id', tostring(orderId))
return 0
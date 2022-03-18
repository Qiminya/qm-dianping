package com.atqm.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.atqm.dto.LoginFormDTO;
import com.atqm.dto.Result;
import com.atqm.dto.UserDTO;
import com.atqm.utils.RedisConstants;
import com.atqm.utils.RegexUtils;
import com.atqm.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.atqm.entity.User;
import com.atqm.mapper.UserMapper;
import com.atqm.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.atqm.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.atqm.utils.RedisConstants.LOGIN_USER_KEY;
import static com.atqm.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.不符合，返回错误信息
            return Result.fail("手机号格式错误！！！");
        }
        // 3.符合生成验证码
        String code = RandomUtil.randomNumbers(6);
//        // 4.保存验证码到session
//        session.setAttribute("code", code);

        // 4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,5, TimeUnit.MINUTES);

        // 5.发送验证码到手机
        System.out.println("验证码:" + code);
        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 1.2 不符合返回错误信息
            return Result.fail("手机号格式错误！");
        }

//        // 2.验证验证码
//        Object cacheCode = session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);

        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            // 3.不一致
            return Result.fail("验证码错误");
        }
        // 4.一直，根据手机号查询用户
        User user = query().eq("phone", phone).one();


        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在,创建新用户
            user = crerateUserWithPhone(phone);
        }

//        // 7.保存用户到session
//        session.setAttribute("user",BeanUtil.copyProperties(user, UserDTO.class));

        // 7.保存用户到redis
        // 7.1 随机生成token作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 7.2 将user对象转存为hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));

        // 7.3存储
        String tokenKey = LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        // 设置token有效期
        stringRedisTemplate.expire(tokenKey,30,TimeUnit.MINUTES);

        return Result.ok(token);
    }

    private User crerateUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(4));
        // 2.保存用户
        save(user);

        return user;
    }
}

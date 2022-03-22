package com.atqm.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.atqm.dto.Result;
import com.atqm.entity.Blog;
import com.atqm.entity.User;
import com.atqm.mapper.BlogMapper;
import com.atqm.service.IBlogService;
import com.atqm.service.IUserService;
import com.atqm.utils.SystemConstants;
import com.atqm.utils.UserHolder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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
public class  BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            isBlogLiked(blog,userId);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogBid(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在！");
        }
        // 2. 查询blog的用户
        queryBlogUser(blog);
        
        // 3.查询blog是否被点赞
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();

        isBlogLiked(blog,userId);

        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog,Long userId) {
        // key
        String key = "blog:liked:"+blog.getId();

        // 2.判断当前用户是否点赞，
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());

        blog.setIsLike(BooleanUtil.isTrue(isMember));

    }

    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();

        // key
        String key = "blog:liked:"+id;

        // 2.判断当前用户是否点赞，
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());

        if(BooleanUtil.isFalse(isMember)){
            // 3.没点赞，可以点赞
            // 3.1数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1")
                    .eq("id", id)
                    .update();
            // 3.2.保存用户到Redis的Set集合
            if(isSuccess){
                stringRedisTemplate.opsForSet().add(key,userId.toString());
            }
        }else {
            // 4.已点赞，取消点赞
            // 4.1 数据库点赞数 -1
            boolean isSuccess = update().setSql("liked = liked - 1")
                    .eq("id", id)
                    .update();
            // 4.2 把用户从redis_set集合中移出
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    // 有blog根据blog查用户
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}

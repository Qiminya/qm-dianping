package com.atqm.service;

import com.atqm.dto.Result;
import com.atqm.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    Result queryBlogBid(Long id);

    Result likeBlog(Long id);
}

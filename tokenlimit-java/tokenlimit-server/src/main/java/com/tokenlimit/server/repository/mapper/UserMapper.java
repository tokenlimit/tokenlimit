package com.tokenlimit.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenlimit.server.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper.
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}

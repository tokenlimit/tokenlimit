package com.tokenlimit.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenlimit.server.entity.ApiKey;
import org.apache.ibatis.annotations.Mapper;

/**
 * API Key Mapper.
 */
@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {
}

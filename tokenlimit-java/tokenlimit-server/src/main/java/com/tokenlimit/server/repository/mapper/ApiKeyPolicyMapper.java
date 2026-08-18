package com.tokenlimit.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenlimit.server.entity.ApiKeyPolicy;
import org.apache.ibatis.annotations.Mapper;

/**
 * API Key 限额策略 Mapper（V6.0 新增）.
 */
@Mapper
public interface ApiKeyPolicyMapper extends BaseMapper<ApiKeyPolicy> {
}

package com.tokenlimit.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenlimit.server.entity.UsageLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 使用日志 Mapper.
 */
@Mapper
public interface UsageLogMapper extends BaseMapper<UsageLog> {
}

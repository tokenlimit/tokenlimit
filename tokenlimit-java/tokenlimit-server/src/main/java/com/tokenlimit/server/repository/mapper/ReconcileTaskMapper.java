package com.tokenlimit.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenlimit.server.entity.ReconcileTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对账任务 Mapper.
 */
@Mapper
public interface ReconcileTaskMapper extends BaseMapper<ReconcileTask> {
}

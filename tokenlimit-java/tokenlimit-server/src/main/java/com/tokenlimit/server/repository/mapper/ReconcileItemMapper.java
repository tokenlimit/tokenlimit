package com.tokenlimit.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenlimit.server.entity.ReconcileItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对账明细 Mapper.
 */
@Mapper
public interface ReconcileItemMapper extends BaseMapper<ReconcileItem> {
}

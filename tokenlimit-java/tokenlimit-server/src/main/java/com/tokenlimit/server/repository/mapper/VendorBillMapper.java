package com.tokenlimit.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenlimit.server.entity.VendorBill;
import org.apache.ibatis.annotations.Mapper;

/**
 * 供应商账单 Mapper.
 */
@Mapper
public interface VendorBillMapper extends BaseMapper<VendorBill> {
}

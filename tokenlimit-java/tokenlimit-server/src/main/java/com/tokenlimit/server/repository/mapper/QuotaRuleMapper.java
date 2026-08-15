package com.tokenlimit.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenlimit.server.entity.QuotaRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 配额规则 Mapper.
 */
@Mapper
public interface QuotaRuleMapper extends BaseMapper<QuotaRule> {
}

package com.tokenlimit.server.service.quota;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tokenlimit.common.enums.Period;
import com.tokenlimit.server.entity.UsageLog;
import com.tokenlimit.server.repository.mapper.UsageLogMapper;
import com.tokenlimit.server.service.redis.QuotaKeyUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 真实用量聚合器：从 MySQL usage_log 聚合规则周期内的真实用量.
 * <p>真实余额 = 配额上限 - 聚合用量；聚合结果用于 Redis balance 缓存的惰性初始化与丢失重建。</p>
 */
@Component
public class QuotaUsageAggregator {

    private final UsageLogMapper usageLogMapper;

    public QuotaUsageAggregator(UsageLogMapper usageLogMapper) {
        this.usageLogMapper = usageLogMapper;
    }

    /**
     * 聚合指定规则在周期内的真实用量.
     * <p>TOKEN → SUM(total_tokens)；COST → SUM(cost)；REQUEST_COUNT → COUNT(*)。</p>
     */
    public long aggregateUsed(String targetType, String targetCode, String limitType,
                              Period period, LocalDateTime now) {
        QueryWrapper<UsageLog> qw = new QueryWrapper<>();
        String column = switch (limitType) {
            case "COST" -> "COALESCE(SUM(cost),0)";
            case "REQUEST_COUNT" -> "COUNT(*)";
            default -> "COALESCE(SUM(total_tokens),0)";
        };
        qw.select(column)
                .eq("USER".equalsIgnoreCase(targetType) ? "user_code" : "team_code", targetCode)
                .ge("created_at", QuotaKeyUtils.periodStart(period, now));
        List<Object> objs = usageLogMapper.selectObjs(qw);
        if (objs.isEmpty() || objs.get(0) == null) {
            return 0;
        }
        return Long.parseLong(objs.get(0).toString());
    }
}

package com.tokenlimit.server.service.quota;

import com.tokenlimit.common.enums.Period;
import com.tokenlimit.server.config.TokenLimitProperties;
import com.tokenlimit.server.entity.QuotaRule;
import com.tokenlimit.server.service.redis.QuotaRedisService;
import org.springframework.stereotype.Component;

/**
 * 团队余额拦截（责任链第 1 环）.
 * <p>检查团队 TOTAL 周期（长期总量）规则的真实余额，任一规则余额不足即拒绝（TEAM_QUOTA_EXCEEDED）。</p>
 */
@Component
public class TeamBalanceInterceptor extends AbstractBalanceInterceptor {

    public TeamBalanceInterceptor(QuotaRedisService quotaRedisService,
                                  QuotaUsageAggregator quotaUsageAggregator,
                                  TokenLimitProperties properties) {
        super(quotaRedisService, quotaUsageAggregator, properties);
    }

    @Override
    public String name() {
        return "team-balance";
    }

    @Override
    public QuotaDenied check(QuotaCheckContext ctx) {
        for (QuotaRule rule : ctx.getTeamRules()) {
            if (Period.TOTAL != Period.valueOf(rule.getPeriod())) {
                continue; // 仅处理长期余额规则，周期用量由 usage-period 拦截
            }
            QuotaDenied denied = checkRule(rule, ctx.getEstTotal(), ctx.isPrecompute(), ctx.getNow());
            if (denied != null) {
                return denied;
            }
        }
        return null;
    }
}

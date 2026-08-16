package com.tokenlimit.server.service.quota;

import com.tokenlimit.common.enums.Period;
import com.tokenlimit.server.config.TokenLimitProperties;
import com.tokenlimit.server.entity.QuotaRule;
import com.tokenlimit.server.service.redis.QuotaRedisService;
import org.springframework.stereotype.Component;

/**
 * 周期用量拦截（责任链第 3 环）.
 * <p>检查周期用量规则（MONTH / WEEK / DAY / HOUR / MINUTE，策略由规则表配置，含"每次请求" REQUEST_COUNT 限次）：</p>
 * <ul>
 *   <li>团队周期规则：任一不足即拒绝（TEAM_QUOTA_EXCEEDED）；</li>
 *   <li>个人周期规则：consumeFrom = PERSONAL 时检查；PERSONAL_FIRST_THEN_TEAM 下个人周期不足时转团队兜底。</li>
 * </ul>
 */
@Component
public class UsagePeriodInterceptor extends AbstractBalanceInterceptor {

    public UsagePeriodInterceptor(QuotaRedisService quotaRedisService,
                                  QuotaUsageAggregator quotaUsageAggregator,
                                  TokenLimitProperties properties) {
        super(quotaRedisService, quotaUsageAggregator, properties);
    }

    @Override
    public String name() {
        return "usage-period";
    }

    @Override
    public QuotaDenied check(QuotaCheckContext ctx) {
        // 团队周期用量（无论何种 quota_mode 均检查）
        for (QuotaRule rule : ctx.getTeamRules()) {
            if (Period.TOTAL == Period.valueOf(rule.getPeriod())) {
                continue; // 长期余额由 team-balance 拦截
            }
            QuotaDenied denied = checkRule(rule, ctx.getEstTotal(), ctx.isPrecompute(), ctx.getNow());
            if (denied != null) {
                return denied;
            }
        }

        // 个人周期用量：consumeFrom = PERSONAL 时检查；TEAM 兜底时个人额度不动
        if (!"PERSONAL".equals(ctx.getConsumeFrom())) {
            return null;
        }
        for (QuotaRule rule : ctx.getUserRules()) {
            if (Period.TOTAL == Period.valueOf(rule.getPeriod())) {
                continue;
            }
            QuotaDenied denied = checkRule(rule, ctx.getEstTotal(), ctx.isPrecompute(), ctx.getNow());
            if (denied != null) {
                // PERSONAL_FIRST_THEN_TEAM：个人周期用量不足 → 团队兜底；PERSONAL_ONLY → 拒绝
                if ("PERSONAL_ONLY".equals(ctx.getQuotaMode())) {
                    return denied;
                }
                ctx.setConsumeFrom("TEAM");
                return null;
            }
        }
        return null;
    }
}

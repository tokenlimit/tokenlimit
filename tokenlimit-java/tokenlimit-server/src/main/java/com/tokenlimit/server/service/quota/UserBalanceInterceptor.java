package com.tokenlimit.server.service.quota;

import com.tokenlimit.common.enums.Period;
import com.tokenlimit.server.config.TokenLimitProperties;
import com.tokenlimit.server.entity.QuotaRule;
import com.tokenlimit.server.service.redis.QuotaRedisService;
import org.springframework.stereotype.Component;

/**
 * 个人余额拦截（责任链第 2 环）.
 * <p>检查用户 TOTAL 周期（长期总量）规则的真实余额，并按 {@code quota_mode} 确定抵扣来源：</p>
 * <ul>
 *   <li>PERSONAL_ONLY：个人余额不足即拒绝（USER_QUOTA_EXCEEDED），consumeFrom = PERSONAL；</li>
 *   <li>TEAM_ONLY：跳过个人余额（团队兜底），consumeFrom = TEAM；</li>
 *   <li>PERSONAL_FIRST_THEN_TEAM：个人余额不足时不拒绝，转团队兜底，consumeFrom = TEAM。</li>
 * </ul>
 */
@Component
public class UserBalanceInterceptor extends AbstractBalanceInterceptor {

    public UserBalanceInterceptor(QuotaRedisService quotaRedisService,
                                  QuotaUsageAggregator quotaUsageAggregator,
                                  TokenLimitProperties properties) {
        super(quotaRedisService, quotaUsageAggregator, properties);
    }

    @Override
    public String name() {
        return "user-balance";
    }

    @Override
    public QuotaDenied check(QuotaCheckContext ctx) {
        switch (ctx.getQuotaMode()) {
            case "TEAM_ONLY" -> {
                ctx.setConsumeFrom("TEAM");
                return null;
            }
            case "PERSONAL_ONLY" -> {
                QuotaDenied denied = checkUserBalance(ctx);
                if (denied != null) {
                    return denied;
                }
                ctx.setConsumeFrom("PERSONAL");
                return null;
            }
            default -> {
                // PERSONAL_FIRST_THEN_TEAM：个人余额不足 → 团队兜底（不拒绝）
                ctx.setConsumeFrom(checkUserBalance(ctx) == null ? "PERSONAL" : "TEAM");
                return null;
            }
        }
    }

    private QuotaDenied checkUserBalance(QuotaCheckContext ctx) {
        for (QuotaRule rule : ctx.getUserRules()) {
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

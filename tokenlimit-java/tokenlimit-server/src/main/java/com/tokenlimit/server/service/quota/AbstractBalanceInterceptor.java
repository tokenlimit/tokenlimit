package com.tokenlimit.server.service.quota;

import com.tokenlimit.common.enums.Period;
import com.tokenlimit.server.config.TokenLimitProperties;
import com.tokenlimit.server.entity.QuotaRule;
import com.tokenlimit.server.service.redis.QuotaRedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/**
 * 余额拦截抽象基类：单条规则的余额检查（含 MySQL 聚合惰性初始化）.
 *
 * <p>检查公式（预计算开关开启时）：{@code balance - pre - est > 0} 放行；关闭时仅 {@code balance - est > 0}。
 * balance 为真实余额（limit - MySQL 聚合用量），pre 为进行中请求的预扣总量。
 * Redis 故障时按 {@code tokenlimit.redis-fallback-enabled} 降级：启用放行（可用性优先），禁用抛出（一致性优先）。</p>
 */
public abstract class AbstractBalanceInterceptor implements QuotaInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AbstractBalanceInterceptor.class);

    protected final QuotaRedisService quotaRedisService;
    protected final QuotaUsageAggregator quotaUsageAggregator;
    protected final TokenLimitProperties properties;

    protected AbstractBalanceInterceptor(QuotaRedisService quotaRedisService,
                                         QuotaUsageAggregator quotaUsageAggregator,
                                         TokenLimitProperties properties) {
        this.quotaRedisService = quotaRedisService;
        this.quotaUsageAggregator = quotaUsageAggregator;
        this.properties = properties;
    }

    /**
     * 检查单条规则：余额充足返回 null，否则返回拒绝.
     * <p>Redis 故障时根据 {@code tokenlimit.redis-fallback-enabled} 决定降级策略：启用时放行，禁用时抛出。</p>
     *
     * @param rule       配额规则
     * @param estTotal   本次预估总 token（REQUEST_COUNT 规则用量为 1）
     * @param precompute 预计算开关
     * @param now        当前时间
     */
    protected QuotaDenied checkRule(QuotaRule rule, long estTotal, boolean precompute, LocalDateTime now) {
        Period period = Period.valueOf(rule.getPeriod());
        long limit = rule.getLimitValue().longValue();
        long amount = amountFor(rule, estTotal);

        try {
            // 真实余额：优先读 Redis 缓存；key 缺失/负值（首次访问/周期滚动/并发超支残留）时从 MySQL 聚合重建
            long balance = quotaRedisService.readBalance(
                    rule.getTargetType(), rule.getTargetCode(), rule.getLimitType(), period, now);
            if (balance < 0) {
                balance = limit - quotaUsageAggregator.aggregateUsed(
                        rule.getTargetType(), rule.getTargetCode(), rule.getLimitType(), period, now);
                quotaRedisService.initBalanceIfAbsent(
                        rule.getTargetType(), rule.getTargetCode(), rule.getLimitType(), period, now, balance);
            }

            long pre = precompute ? quotaRedisService.readPre(
                    rule.getTargetType(), rule.getTargetCode(), rule.getLimitType(), period, now) : 0;

            if (balance - pre - amount <= 0) {
                return new QuotaDenied(errorCodeFor(rule),
                        "配额超限: " + rule.getTargetType() + "/" + rule.getTargetCode()
                                + " " + period + " 余额 " + balance
                                + " 预扣 " + pre + " 预估 " + amount + " 上限 " + limit);
            }
            return null;
        } catch (Exception e) {
            log.warn("Redis 配额读取失败，降级策略: {}",
                    properties.isRedisFallbackEnabled() ? "放行" : "拒绝", e);
            if (properties.isRedisFallbackEnabled()) {
                return null; // Redis 故障时放行（可用性优先）
            }
            throw e; // Redis 故障时拒绝（一致性优先）
        }
    }

    /**
     * 本次规则扣减量：REQUEST_COUNT 规则为 1，其余为预估总 token.
     */
    protected long amountFor(QuotaRule rule, long estTotal) {
        return "REQUEST_COUNT".equals(rule.getLimitType()) ? 1 : estTotal;
    }

    /**
     * 规则所属错误码：USER 规则 → USER_QUOTA_EXCEEDED，其余 → TEAM_QUOTA_EXCEEDED.
     */
    protected String errorCodeFor(QuotaRule rule) {
        return "USER".equalsIgnoreCase(rule.getTargetType())
                ? "USER_QUOTA_EXCEEDED" : "TEAM_QUOTA_EXCEEDED";
    }
}

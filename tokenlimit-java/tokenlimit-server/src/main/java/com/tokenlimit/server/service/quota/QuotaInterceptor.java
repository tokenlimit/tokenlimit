package com.tokenlimit.server.service.quota;

/**
 * 配额拦截器（责任链模式）.
 * <p>按 {@code tokenlimit.quota-chain} 配置顺序执行，任一环节返回非 null 即拦截，不再执行后续环节。</p>
 * <p>内置拦截器（可配置裁剪/排序）：</p>
 * <ul>
 *   <li>{@code team-balance}：Team 余额拦截（TOTAL 周期规则）</li>
 *   <li>{@code user-balance}：个人余额拦截（TOTAL 周期规则，并确定 consumeFrom）</li>
 *   <li>{@code usage-period}：周期用量拦截（MONTH/WEEK/DAY/HOUR/MINUTE 规则，策略由规则表配置）</li>
 * </ul>
 */
public interface QuotaInterceptor {

    /** 拦截器标识（对应 quota-chain 配置项） */
    String name();

    /**
     * 执行拦截检查.
     *
     * @return null=通过；否则返回拒绝结果（错误码 + 说明）
     */
    QuotaDenied check(QuotaCheckContext ctx);
}

package com.tokenlimit.server.service.quota;

import com.tokenlimit.server.entity.ApiKey;
import com.tokenlimit.server.entity.QuotaRule;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 配额拦截上下文（责任链各环节共享的可变状态）.
 * <p>链执行过程中确定 {@code consumeFrom}（抵扣来源），并记录本次预扣的适用规则：</p>
 * <ul>
 *   <li>PERSONAL：个人额度足够，User + Team 规则同时预扣/结算；</li>
 *   <li>TEAM：个人不足团队兜底（或 TEAM_ONLY），仅 Team 规则预扣/结算。</li>
 * </ul>
 */
public class QuotaCheckContext {

    private final ApiKey apiKey;
    /** User.quota_mode：PERSONAL_ONLY / TEAM_ONLY / PERSONAL_FIRST_THEN_TEAM */
    private final String quotaMode;
    private final List<QuotaRule> teamRules;
    private final List<QuotaRule> userRules;
    /** 本次预估总 token（jtokkit） */
    private final long estTotal;
    /** 预计算拦截开关（check 时是否预扣） */
    private final boolean precompute;
    private final LocalDateTime now;
    /** 抵扣来源：PERSONAL / TEAM（链执行过程中确定） */
    private String consumeFrom;

    public QuotaCheckContext(ApiKey apiKey, String quotaMode,
                             List<QuotaRule> teamRules, List<QuotaRule> userRules,
                             long estTotal, boolean precompute, LocalDateTime now) {
        this.apiKey = apiKey;
        this.quotaMode = quotaMode;
        this.teamRules = teamRules;
        this.userRules = userRules;
        this.estTotal = estTotal;
        this.precompute = precompute;
        this.now = now;
    }

    public ApiKey getApiKey() {
        return apiKey;
    }

    public String getQuotaMode() {
        return quotaMode;
    }

    public List<QuotaRule> getTeamRules() {
        return teamRules;
    }

    public List<QuotaRule> getUserRules() {
        return userRules;
    }

    public long getEstTotal() {
        return estTotal;
    }

    public boolean isPrecompute() {
        return precompute;
    }

    public LocalDateTime getNow() {
        return now;
    }

    public String getConsumeFrom() {
        return consumeFrom;
    }

    public void setConsumeFrom(String consumeFrom) {
        this.consumeFrom = consumeFrom;
    }
}

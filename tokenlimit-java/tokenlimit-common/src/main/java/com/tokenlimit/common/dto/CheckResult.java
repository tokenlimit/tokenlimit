package com.tokenlimit.common.dto;

import java.io.Serializable;

/**
 * 配额检查结果（PRD V5.2）.
 * <p>V5.2 责任链拦截 + 预计算开关：开启时 check 按 jtokkit 预估量原子预扣（真实余额 - 预扣值 &lt;= 0 拦截），
 * report 阶段回滚预扣、按真实用量扣减余额；关闭时 check 只读余额不预扣。</p>
 */
public class CheckResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否放行 */
    private boolean allowed;
    /** 追踪 ID（allowed=true 时生成） */
    private String traceId;
    /** 抵扣来源：PERSONAL / TEAM */
    private String consumeFrom;
    /** 剩余配额（allowed=true 时返回，对应已用配额规则最小余量） */
    private long remainTokens;
    /** 拒绝原因码（allowed=false 时有值，如 TEAM_QUOTA_EXCEEDED） */
    private String reason;
    /** 拒绝说明（allowed=false 时有值） */
    private String message;

    /** 预估 prompt tokens（V5.0） */
    private long estimatedPromptTokens;
    /** 预估 completion tokens（V5.0） */
    private long estimatedCompletionTokens;
    /** 预估 total tokens（V5.0） */
    private long estimatedTotalTokens;

    public CheckResult() {
    }

    public CheckResult(boolean allowed, String traceId, String consumeFrom, long remainTokens,
                       String reason, String message) {
        this.allowed = allowed;
        this.traceId = traceId;
        this.consumeFrom = consumeFrom;
        this.remainTokens = remainTokens;
        this.reason = reason;
        this.message = message;
    }

    public static CheckResult allowed(String traceId, String consumeFrom, long remainTokens) {
        return new CheckResult(true, traceId, consumeFrom, remainTokens, null, null);
    }

    public static CheckResult denied(String reason, String message) {
        return new CheckResult(false, null, null, 0, reason, message);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getConsumeFrom() {
        return consumeFrom;
    }

    public void setConsumeFrom(String consumeFrom) {
        this.consumeFrom = consumeFrom;
    }

    public long getRemainTokens() {
        return remainTokens;
    }

    public void setRemainTokens(long remainTokens) {
        this.remainTokens = remainTokens;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getEstimatedPromptTokens() {
        return estimatedPromptTokens;
    }

    public void setEstimatedPromptTokens(long estimatedPromptTokens) {
        this.estimatedPromptTokens = estimatedPromptTokens;
    }

    public long getEstimatedCompletionTokens() {
        return estimatedCompletionTokens;
    }

    public void setEstimatedCompletionTokens(long estimatedCompletionTokens) {
        this.estimatedCompletionTokens = estimatedCompletionTokens;
    }

    public long getEstimatedTotalTokens() {
        return estimatedTotalTokens;
    }

    public void setEstimatedTotalTokens(long estimatedTotalTokens) {
        this.estimatedTotalTokens = estimatedTotalTokens;
    }
}

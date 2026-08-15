package com.tokenlimit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 用量日志（V5.0）.
 * <p>V5 同时记录预估值（estimated_*）与供应商真实值（*_tokens），并通过 usage_source 标识当前记录来自哪一侧；</p>
 * <p>当预估值与真实值偏差超过阈值时标记 anomaly_detected 并记录 anomaly_detail。</p>
 */
@TableName("tl_usage_log")
public class UsageLog extends BaseEntity {

    /** 调用链路 traceId */
    private String traceId;

    /** 团队编码 */
    private String teamCode;

    /** 用户编码 */
    private String userCode;

    /** API Key ID */
    private Long apiKeyId;

    /** 模型标识 */
    private String model;

    /** 供应商：openai / deepseek / ... */
    private String provider;

    /** 预估 prompt tokens（jtokkit） */
    private Long estimatedPromptTokens;

    /** 预估 completion tokens（jtokkit） */
    private Long estimatedCompletionTokens;

    /** 预估总 tokens */
    private Long estimatedTotalTokens;

    /** 供应商真实 prompt tokens */
    private Long promptTokens;

    /** 供应商真实 completion tokens */
    private Long completionTokens;

    /** 供应商真实总 tokens */
    private Long totalTokens;

    /** 费用（MVP 阶段可为 0） */
    private Long cost;

    /** 额度消耗来源：TEAM / USER */
    private String consumeFrom;

    /** 记录来源：PROVIDER（厂商真实值）/ ESTIMATED（本地预估值） */
    private String usageSource;

    /** 状态：SUCCESS / FAILED / INTERRUPTED / ERROR / CANCELLED */
    private String status;

    /** 是否异常（预估值与真实值偏差超阈值） */
    private Boolean anomalyDetected;

    /** 异常详情（偏差说明等） */
    private String anomalyDetail;

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getTeamCode() {
        return teamCode;
    }

    public void setTeamCode(String teamCode) {
        this.teamCode = teamCode;
    }

    public String getUserCode() {
        return userCode;
    }

    public void setUserCode(String userCode) {
        this.userCode = userCode;
    }

    public Long getApiKeyId() {
        return apiKeyId;
    }

    public void setApiKeyId(Long apiKeyId) {
        this.apiKeyId = apiKeyId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Long getEstimatedPromptTokens() {
        return estimatedPromptTokens;
    }

    public void setEstimatedPromptTokens(Long estimatedPromptTokens) {
        this.estimatedPromptTokens = estimatedPromptTokens;
    }

    public Long getEstimatedCompletionTokens() {
        return estimatedCompletionTokens;
    }

    public void setEstimatedCompletionTokens(Long estimatedCompletionTokens) {
        this.estimatedCompletionTokens = estimatedCompletionTokens;
    }

    public Long getEstimatedTotalTokens() {
        return estimatedTotalTokens;
    }

    public void setEstimatedTotalTokens(Long estimatedTotalTokens) {
        this.estimatedTotalTokens = estimatedTotalTokens;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Long getCost() {
        return cost;
    }

    public void setCost(Long cost) {
        this.cost = cost;
    }

    public String getConsumeFrom() {
        return consumeFrom;
    }

    public void setConsumeFrom(String consumeFrom) {
        this.consumeFrom = consumeFrom;
    }

    public String getUsageSource() {
        return usageSource;
    }

    public void setUsageSource(String usageSource) {
        this.usageSource = usageSource;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getAnomalyDetected() {
        return anomalyDetected;
    }

    public void setAnomalyDetected(Boolean anomalyDetected) {
        this.anomalyDetected = anomalyDetected;
    }

    public String getAnomalyDetail() {
        return anomalyDetail;
    }

    public void setAnomalyDetail(String anomalyDetail) {
        this.anomalyDetail = anomalyDetail;
    }
}

package com.tokenlimit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;

/**
 * 用量日志（V5.0 / 计费快照 V5.3 / 缓存计费 V5.4）.
 * <p>V5 同时记录预估值（estimated_*）与供应商真实值（*_tokens），并通过 usage_source 标识当前记录来自哪一侧；</p>
 * <p>当预估值与真实值偏差超过阈值时标记 anomaly_detected 并记录 anomaly_detail。</p>
 * <p>计费快照（Billing Snapshot）：写入后费用、单价、汇率永久固化，后续修改价格/汇率只影响新调用；
 * 报表必须基于 cost 字段 SUM 聚合，不得用当前价格动态重算历史数据。</p>
 * <p>缓存计费（V5.4）：cached_tokens（OpenAI cached_tokens / DeepSeek prompt_cache_hit_tokens /
 * Anthropic cache_read_input_tokens）按缓存读取单价计费，cache_write_tokens（Anthropic
 * cache_creation_input_tokens）按缓存写入单价计费；缓存单价同样固化为快照。</p>
 */
@TableName("tl_usage_log")
public class UsageLog extends BaseEntity {

    /** 调用链路 traceId */
    private String traceId;

    /** 团队编码 */
    private String teamCode;

    /** 用户编码 */
    private String userCode;

    /** API Key 标识（tl_api_key.key_id） */
    private String apiKeyId;

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

    /** 计费快照：本位币费用（如 CNY，核心扣费/报表字段，写入后不可变） */
    private BigDecimal cost;

    /** 计费快照：原始币种费用（如 USD） */
    private BigDecimal costOriginal;

    /** 计费快照：模型原始计价币种（USD / CNY） */
    private String currency;

    /** 计费快照：调用时输入单价（每 Token） */
    private BigDecimal inputPriceSnapshot;

    /** 计费快照：调用时输出单价（每 Token） */
    private BigDecimal outputPriceSnapshot;

    /** 计费快照：调用时汇率（原始币种→本位币） */
    private BigDecimal exchangeRateSnapshot;

    /** 计费快照：企业本位币 */
    private String baseCurrency;

    /** 缓存命中 token（OpenAI cached_tokens / DeepSeek prompt_cache_hit_tokens / Anthropic cache_read_input_tokens） */
    private Long cachedTokens;

    /** 缓存写入 token（Anthropic cache_creation_input_tokens） */
    private Long cacheWriteTokens;

    /** 计费快照：调用时缓存读取单价（每 Token，未配置为 null） */
    private BigDecimal cacheReadPriceSnapshot;

    /** 计费快照：调用时缓存写入单价（每 Token，未配置为 null） */
    private BigDecimal cacheWritePriceSnapshot;

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

    public String getApiKeyId() {
        return apiKeyId;
    }

    public void setApiKeyId(String apiKeyId) {
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

    public BigDecimal getCost() {
        return cost;
    }

    public void setCost(BigDecimal cost) {
        this.cost = cost;
    }

    public BigDecimal getCostOriginal() {
        return costOriginal;
    }

    public void setCostOriginal(BigDecimal costOriginal) {
        this.costOriginal = costOriginal;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getInputPriceSnapshot() {
        return inputPriceSnapshot;
    }

    public void setInputPriceSnapshot(BigDecimal inputPriceSnapshot) {
        this.inputPriceSnapshot = inputPriceSnapshot;
    }

    public BigDecimal getOutputPriceSnapshot() {
        return outputPriceSnapshot;
    }

    public void setOutputPriceSnapshot(BigDecimal outputPriceSnapshot) {
        this.outputPriceSnapshot = outputPriceSnapshot;
    }

    public BigDecimal getExchangeRateSnapshot() {
        return exchangeRateSnapshot;
    }

    public void setExchangeRateSnapshot(BigDecimal exchangeRateSnapshot) {
        this.exchangeRateSnapshot = exchangeRateSnapshot;
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public void setBaseCurrency(String baseCurrency) {
        this.baseCurrency = baseCurrency;
    }

    public Long getCachedTokens() {
        return cachedTokens;
    }

    public void setCachedTokens(Long cachedTokens) {
        this.cachedTokens = cachedTokens;
    }

    public Long getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    public void setCacheWriteTokens(Long cacheWriteTokens) {
        this.cacheWriteTokens = cacheWriteTokens;
    }

    public BigDecimal getCacheReadPriceSnapshot() {
        return cacheReadPriceSnapshot;
    }

    public void setCacheReadPriceSnapshot(BigDecimal cacheReadPriceSnapshot) {
        this.cacheReadPriceSnapshot = cacheReadPriceSnapshot;
    }

    public BigDecimal getCacheWritePriceSnapshot() {
        return cacheWritePriceSnapshot;
    }

    public void setCacheWritePriceSnapshot(BigDecimal cacheWritePriceSnapshot) {
        this.cacheWritePriceSnapshot = cacheWritePriceSnapshot;
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

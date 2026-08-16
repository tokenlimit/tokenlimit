package com.tokenlimit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型价格实体（价格管理 / 计费基准）.
 * <p>价格单位为每 1 个 Token 的单价（避免计算时频繁除以 1000000）；
 * 修改价格只影响新调用——usage_log 已固化为计费快照，历史费用不可变。</p>
 * status: ENABLED / DISABLED
 */
@TableName("tl_model_price")
public class ModelPrice extends BaseEntity {

    private String provider;
    private String model;
    /** 输入单价（每 Token） */
    private BigDecimal inputPricePerToken;
    /** 输出单价（每 Token） */
    private BigDecimal outputPricePerToken;
    /** 缓存读取单价（Anthropic Prompt Caching 等，预留） */
    private BigDecimal cacheReadPricePerToken;
    /** 缓存写入单价（预留） */
    private BigDecimal cacheWritePricePerToken;
    /** 币种：USD / CNY */
    private String currency;
    private String status;
    /** 生效时间（记录最近一次改价时间） */
    private LocalDateTime effectiveAt;
    private String createdBy;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public BigDecimal getInputPricePerToken() {
        return inputPricePerToken;
    }

    public void setInputPricePerToken(BigDecimal inputPricePerToken) {
        this.inputPricePerToken = inputPricePerToken;
    }

    public BigDecimal getOutputPricePerToken() {
        return outputPricePerToken;
    }

    public void setOutputPricePerToken(BigDecimal outputPricePerToken) {
        this.outputPricePerToken = outputPricePerToken;
    }

    public BigDecimal getCacheReadPricePerToken() {
        return cacheReadPricePerToken;
    }

    public void setCacheReadPricePerToken(BigDecimal cacheReadPricePerToken) {
        this.cacheReadPricePerToken = cacheReadPricePerToken;
    }

    public BigDecimal getCacheWritePricePerToken() {
        return cacheWritePricePerToken;
    }

    public void setCacheWritePricePerToken(BigDecimal cacheWritePricePerToken) {
        this.cacheWritePricePerToken = cacheWritePricePerToken;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getEffectiveAt() {
        return effectiveAt;
    }

    public void setEffectiveAt(LocalDateTime effectiveAt) {
        this.effectiveAt = effectiveAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}

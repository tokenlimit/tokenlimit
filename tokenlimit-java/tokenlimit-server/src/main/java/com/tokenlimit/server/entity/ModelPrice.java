package com.tokenlimit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;

/**
 * 模型价格实体（对账中心 - 价格管理）.
 * status: ACTIVE / INACTIVE
 */
@TableName("tl_model_price")
public class ModelPrice extends BaseEntity {

    private String provider;
    private String model;
    /** 输入单价（元/百万token） */
    private BigDecimal inputPrice;
    /** 输出单价（元/百万token） */
    private BigDecimal outputPrice;
    private String currency;
    private String status;
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

    public BigDecimal getInputPrice() {
        return inputPrice;
    }

    public void setInputPrice(BigDecimal inputPrice) {
        this.inputPrice = inputPrice;
    }

    public BigDecimal getOutputPrice() {
        return outputPrice;
    }

    public void setOutputPrice(BigDecimal outputPrice) {
        this.outputPrice = outputPrice;
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

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}

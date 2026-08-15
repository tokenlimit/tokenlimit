package com.tokenlimit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 供应商账单实体（对账中心 - 账单导入）.
 * status: ACTIVE / INACTIVE
 */
@TableName("tl_vendor_bill")
public class VendorBill extends BaseEntity {

    private LocalDate billDate;
    private String provider;
    private String model;
    private Long providerTokens;
    private BigDecimal providerCost;
    private String currency;
    private String status;
    private String remark;

    public LocalDate getBillDate() {
        return billDate;
    }

    public void setBillDate(LocalDate billDate) {
        this.billDate = billDate;
    }

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

    public Long getProviderTokens() {
        return providerTokens;
    }

    public void setProviderTokens(Long providerTokens) {
        this.providerTokens = providerTokens;
    }

    public BigDecimal getProviderCost() {
        return providerCost;
    }

    public void setProviderCost(BigDecimal providerCost) {
        this.providerCost = providerCost;
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

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}

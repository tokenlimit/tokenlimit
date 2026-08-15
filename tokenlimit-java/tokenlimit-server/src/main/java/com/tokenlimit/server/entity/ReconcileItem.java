package com.tokenlimit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 对账明细实体.
 * status: CONSISTENT(一致) / DIFFERENCE(差异) / PENDING(待处理) / DISPUTED(争议)
 */
@TableName("tl_reconcile_item")
public class ReconcileItem extends BaseEntity {

    private Long taskId;
    private LocalDate billDate;
    private String provider;
    private String model;
    private String teamCode;
    private Long ourTokens;
    private Long providerTokens;
    private Long tokenDiff;
    private BigDecimal tokenDiffRate;
    private BigDecimal ourCost;
    private BigDecimal providerCost;
    private BigDecimal costDiff;
    private BigDecimal costDiffRate;
    private String status;
    private String remark;

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

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

    public String getTeamCode() {
        return teamCode;
    }

    public void setTeamCode(String teamCode) {
        this.teamCode = teamCode;
    }

    public Long getOurTokens() {
        return ourTokens;
    }

    public void setOurTokens(Long ourTokens) {
        this.ourTokens = ourTokens;
    }

    public Long getProviderTokens() {
        return providerTokens;
    }

    public void setProviderTokens(Long providerTokens) {
        this.providerTokens = providerTokens;
    }

    public Long getTokenDiff() {
        return tokenDiff;
    }

    public void setTokenDiff(Long tokenDiff) {
        this.tokenDiff = tokenDiff;
    }

    public BigDecimal getTokenDiffRate() {
        return tokenDiffRate;
    }

    public void setTokenDiffRate(BigDecimal tokenDiffRate) {
        this.tokenDiffRate = tokenDiffRate;
    }

    public BigDecimal getOurCost() {
        return ourCost;
    }

    public void setOurCost(BigDecimal ourCost) {
        this.ourCost = ourCost;
    }

    public BigDecimal getProviderCost() {
        return providerCost;
    }

    public void setProviderCost(BigDecimal providerCost) {
        this.providerCost = providerCost;
    }

    public BigDecimal getCostDiff() {
        return costDiff;
    }

    public void setCostDiff(BigDecimal costDiff) {
        this.costDiff = costDiff;
    }

    public BigDecimal getCostDiffRate() {
        return costDiffRate;
    }

    public void setCostDiffRate(BigDecimal costDiffRate) {
        this.costDiffRate = costDiffRate;
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

package com.tokenlimit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账任务实体.
 * status: PENDING / RUNNING / COMPLETED / FAILED
 */
@TableName("tl_reconcile_task")
public class ReconcileTask extends BaseEntity {

    private String taskCode;
    private LocalDate billDate;
    private String provider;
    private String status;
    private Integer totalItems;
    private Integer diffItems;
    private Integer disputeItems;
    private BigDecimal avgDiffRate;
    private LocalDateTime executedAt;
    private String remark;
    private String createdBy;

    public String getTaskCode() {
        return taskCode;
    }

    public void setTaskCode(String taskCode) {
        this.taskCode = taskCode;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getTotalItems() {
        return totalItems;
    }

    public void setTotalItems(Integer totalItems) {
        this.totalItems = totalItems;
    }

    public Integer getDiffItems() {
        return diffItems;
    }

    public void setDiffItems(Integer diffItems) {
        this.diffItems = diffItems;
    }

    public Integer getDisputeItems() {
        return disputeItems;
    }

    public void setDisputeItems(Integer disputeItems) {
        this.disputeItems = disputeItems;
    }

    public BigDecimal getAvgDiffRate() {
        return avgDiffRate;
    }

    public void setAvgDiffRate(BigDecimal avgDiffRate) {
        this.avgDiffRate = avgDiffRate;
    }

    public LocalDateTime getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(LocalDateTime executedAt) {
        this.executedAt = executedAt;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}

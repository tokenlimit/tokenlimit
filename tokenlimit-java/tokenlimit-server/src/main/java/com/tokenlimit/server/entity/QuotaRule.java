package com.tokenlimit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 配额规则（V5.0）.
 * <p>V5.1 预扣减模型：规则描述「谁（targetType+targetCode）+ 哪个模型（model）+ 哪种额度（limitType）+ 限额（limitValue）+ 周期（period）」，
 * check 按预估量预扣、report 回滚预扣后按真实值扣减。</p>
 * <p>配额状态：ENABLED / DISABLED。</p>
 */
@TableName("tl_quota_rule")
public class QuotaRule extends BaseEntity {

    /** 配额对象类型：TEAM / USER */
    private String targetType;

    /** 配额对象编码：teamCode / userCode */
    private String targetCode;

    /** 模型标识，null 或 * 表示全部模型 */
    private String model;

    /** 额度类型：TOKEN（token 数）/ CALL（调用次数） */
    private String limitType;

    /** 周期：DAY / MONTH / TOTAL */
    private String period;

    /** 限额数值 */
    private Long limitValue;

    /** 规则状态：ENABLED / DISABLED */
    private String status;

    /** 描述 */
    private String description;

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetCode() {
        return targetCode;
    }

    public void setTargetCode(String targetCode) {
        this.targetCode = targetCode;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getLimitType() {
        return limitType;
    }

    public void setLimitType(String limitType) {
        this.limitType = limitType;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public Long getLimitValue() {
        return limitValue;
    }

    public void setLimitValue(Long limitValue) {
        this.limitValue = limitValue;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}

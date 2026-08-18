package com.tokenlimit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * API Key 限额策略实体（V6.0 新增，User 自助风控）.
 * <p>存储 API Key 级别的细粒度限额策略，由 End User 自主设置：</p>
 * <ul>
 *   <li>单次请求最大 token 数（防异常大请求）</li>
 *   <li>小时限额（小时熔断）</li>
 *   <li>日限额</li>
 *   <li>冻结状态控制</li>
 * </ul>
 */
@TableName("tl_api_key_policy")
public class ApiKeyPolicy extends BaseEntity {

    /** API Key access_key（关联 tl_api_key.access_key） */
    private String accessKey;
    
    /** 所属团队编码 */
    private String teamCode;
    
    /** 绑定用户编码 */
    private String userCode;
    
    /** API Key 标识 */
    private String keyId;
    
    /** 单次请求最大 token 数（NULL 表示不限制） */
    private Long maxTokensPerRequest;
    
    /** 小时限额（token 数，NULL 表示不限制） */
    private Long hourlyLimit;
    
    /** 小时已用量（Redis 同步值） */
    private Long hourlyUsed;
    
    /** 小时限额重置时间 */
    private LocalDateTime hourlyResetAt;
    
    /** 日限额（token 数，NULL 表示不限制） */
    private Long dailyLimit;
    
    /** 日已用量（Redis 同步值） */
    private Long dailyUsed;
    
    /** 日限额重置时间 */
    private LocalDateTime dailyResetAt;
    
    /** 是否冻结：1 冻结/0 正常（用户手动或系统自动） */
    private Boolean isFrozen;
    
    /** 冻结原因 */
    private String frozenReason;
    
    /** 状态：ENABLED/DISABLED */
    private String status;

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
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

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public Long getMaxTokensPerRequest() {
        return maxTokensPerRequest;
    }

    public void setMaxTokensPerRequest(Long maxTokensPerRequest) {
        this.maxTokensPerRequest = maxTokensPerRequest;
    }

    public Long getHourlyLimit() {
        return hourlyLimit;
    }

    public void setHourlyLimit(Long hourlyLimit) {
        this.hourlyLimit = hourlyLimit;
    }

    public Long getHourlyUsed() {
        return hourlyUsed;
    }

    public void setHourlyUsed(Long hourlyUsed) {
        this.hourlyUsed = hourlyUsed;
    }

    public LocalDateTime getHourlyResetAt() {
        return hourlyResetAt;
    }

    public void setHourlyResetAt(LocalDateTime hourlyResetAt) {
        this.hourlyResetAt = hourlyResetAt;
    }

    public Long getDailyLimit() {
        return dailyLimit;
    }

    public void setDailyLimit(Long dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    public Long getDailyUsed() {
        return dailyUsed;
    }

    public void setDailyUsed(Long dailyUsed) {
        this.dailyUsed = dailyUsed;
    }

    public LocalDateTime getDailyResetAt() {
        return dailyResetAt;
    }

    public void setDailyResetAt(LocalDateTime dailyResetAt) {
        this.dailyResetAt = dailyResetAt;
    }

    public Boolean getIsFrozen() {
        return isFrozen;
    }

    public void setIsFrozen(Boolean isFrozen) {
        this.isFrozen = isFrozen;
    }

    public String getFrozenReason() {
        return frozenReason;
    }

    public void setFrozenReason(String frozenReason) {
        this.frozenReason = frozenReason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

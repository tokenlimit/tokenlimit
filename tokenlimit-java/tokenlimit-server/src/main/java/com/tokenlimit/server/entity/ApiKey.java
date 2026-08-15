package com.tokenlimit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * API Key 实体（强绑定 team/user）.
 * status: ENABLED / DISABLED / EXPIRED / REVOKED
 * access_key 全局唯一（客户端调用凭证，格式 tl_ak_xxx）
 * secret 明文仅创建/重置时返回一次（secretHash 存储）
 */
@TableName("tl_api_key")
public class ApiKey extends BaseEntity {

    private String teamCode;
    private String userCode;
    /** API Key 标识（内部唯一，如 key-xxxx） */
    private String keyId;
    /** Key 名称（便于识别用途） */
    private String keyName;
    private String accessKey;
    private String secretHash;
    private String status;
    private LocalDateTime expireAt;
    private LocalDateTime lastUsedAt;
    private String createdBy;

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

    public String getKeyName() {
        return keyName;
    }

    public void setKeyName(String keyName) {
        this.keyName = keyName;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public void setSecretHash(String secretHash) {
        this.secretHash = secretHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}

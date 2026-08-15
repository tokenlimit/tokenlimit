package com.tokenlimit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 供应商密钥凭证实体（PRD V4.0）.
 * <p>真实大模型 Key 加密存储、永不回显；scopeType: GLOBAL / TEAM；
 * status: ACTIVE / INACTIVE。</p>
 */
@TableName("tl_provider_credential")
public class ProviderCredential extends BaseEntity {

    /** 凭证编码（全局唯一） */
    private String credentialCode;
    /** 供应商编码，如 openai/anthropic/deepseek */
    private String provider;
    /** 供应商名称 */
    private String providerName;
    /** 凭证名称（便于识别） */
    private String credentialName;
    /** 作用域：GLOBAL / TEAM */
    private String scopeType;
    /** 所属团队（scopeType=TEAM 时必填） */
    private String teamCode;
    /** 上游 Base URL（转发目标，可为空则使用 provider 默认地址） */
    private String apiBaseUrl;
    /** 上游 API Key（AES 加密存储，永不回显） */
    private String apiKeyEnc;
    /** 绑定模型（可为空表示该凭证可服务所有模型） */
    private String model;
    /** 状态：ACTIVE / INACTIVE */
    private String status;
    private String remark;
    private String createdBy;

    public String getCredentialCode() {
        return credentialCode;
    }

    public void setCredentialCode(String credentialCode) {
        this.credentialCode = credentialCode;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getCredentialName() {
        return credentialName;
    }

    public void setCredentialName(String credentialName) {
        this.credentialName = credentialName;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public String getTeamCode() {
        return teamCode;
    }

    public void setTeamCode(String teamCode) {
        this.teamCode = teamCode;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getApiKeyEnc() {
        return apiKeyEnc;
    }

    public void setApiKeyEnc(String apiKeyEnc) {
        this.apiKeyEnc = apiKeyEnc;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
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

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}

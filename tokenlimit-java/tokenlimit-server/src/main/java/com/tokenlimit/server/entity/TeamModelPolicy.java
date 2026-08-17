package com.tokenlimit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 团队模型策略实体（PRD V4.0）.
 * <p>team + model 绑定使用的凭证；查找优先级：Team 专属凭证 → GLOBAL 凭证 → PROVIDER_NOT_FOUND。</p>
 */
@TableName("tl_team_model_policy")
public class TeamModelPolicy extends BaseEntity {

    /** 团队编码 */
    private String teamCode;
    /** 模型（为空表示全部模型） */
    private String model;
    /** 凭证编码（tl_provider_credential.credential_code） */
    private String credentialCode;
    /** 是否启用 */
    private Boolean enabled;
    private String remark;
    private String createdBy;

    public String getTeamCode() {
        return teamCode;
    }

    public void setTeamCode(String teamCode) {
        this.teamCode = teamCode;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getCredentialCode() {
        return credentialCode;
    }

    public void setCredentialCode(String credentialCode) {
        this.credentialCode = credentialCode;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
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

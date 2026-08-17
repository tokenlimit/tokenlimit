package com.tokenlimit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 团队实体（核心预算池 / 成本中心 / 账号边界 / 密钥边界）.
 * teamType: TEAM / DEPARTMENT / APPLICATION / PROJECT / CUSTOMER / COST_CENTER
 */
@TableName("tl_team")
public class Team extends BaseEntity {

    private String teamCode;
    private String teamName;
    private String teamType;
    private String description;
    /** 状态：ENABLED / DISABLED */
    private String status;
    private String createdBy;

    public String getTeamCode() {
        return teamCode;
    }

    public void setTeamCode(String teamCode) {
        this.teamCode = teamCode;
    }

    public String getTeamName() {
        return teamName;
    }

    public void setTeamName(String teamName) {
        this.teamName = teamName;
    }

    public String getTeamType() {
        return teamType;
    }

    public void setTeamType(String teamType) {
        this.teamType = teamType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

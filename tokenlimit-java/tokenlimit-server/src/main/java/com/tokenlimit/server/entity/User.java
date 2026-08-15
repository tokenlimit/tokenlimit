package com.tokenlimit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户实体（员工 / 终端客户 / 机器人账号 / 服务 / 系统）.
 * userType: EMPLOYEE / END_CUSTOMER / BOT / SERVICE / SYSTEM
 * quotaMode: PERSONAL_ONLY / TEAM_ONLY / PERSONAL_FIRST_THEN_TEAM
 * role: USER / TEAM_ADMIN / ADMIN
 * username 全局唯一（登录账号）；user_code 在同一团队下唯一
 */
@TableName("tl_user")
public class User extends BaseEntity {

    private String teamCode;
    private String userCode;
    private String userName;
    private String userType;
    private String quotaMode;
    /** 角色：USER / TEAM_ADMIN / ADMIN */
    private String role;
    /** 登录账号（全局唯一，NULL 表示不可登录，如 BOT/SYSTEM） */
    private String username;
    /** 登录密码哈希（SHA-256，明文仅首次创建返回） */
    private String passwordHash;
    /** 是否允许登录：1 允许 / 0 禁止 */
    private Boolean loginEnabled;
    /** 最后登录时间 */
    private LocalDateTime lastLoginAt;
    /** 密码修改时间（NULL 表示尚未修改，首次登录需强制改密） */
    private LocalDateTime passwordChangedAt;
    /** 状态：ENABLED / DISABLED */
    private String status;

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

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    public String getQuotaMode() {
        return quotaMode;
    }

    public void setQuotaMode(String quotaMode) {
        this.quotaMode = quotaMode;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Boolean getLoginEnabled() {
        return loginEnabled;
    }

    public void setLoginEnabled(Boolean loginEnabled) {
        this.loginEnabled = loginEnabled;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public LocalDateTime getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public void setPasswordChangedAt(LocalDateTime passwordChangedAt) {
        this.passwordChangedAt = passwordChangedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

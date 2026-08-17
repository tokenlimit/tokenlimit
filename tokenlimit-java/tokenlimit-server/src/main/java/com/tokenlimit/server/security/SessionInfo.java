package com.tokenlimit.server.security;

import com.tokenlimit.server.entity.User;

import java.time.LocalDateTime;

/**
 * 登录身份快照（登录时从 tl_user 固化，编码进 JWT，避免后续每次请求查库）.
 * <p>由 {@link JwtTokenProvider} 编入/解析 JWT claims，作为
 * {@code SecurityContext} 中 authentication 的 principal。</p>
 */
public class SessionInfo {

    private String username;
    private String userName;
    private String role;
    private String teamCode;
    private String userCode;
    private boolean mustChangePassword;
    private LocalDateTime loginAt;

    public SessionInfo() {
    }

    public SessionInfo(User user, boolean mustChangePassword) {
        this.username = user.getUsername();
        this.userName = user.getUserName();
        this.role = user.getRole();
        this.teamCode = user.getTeamCode();
        this.userCode = user.getUserCode();
        this.mustChangePassword = mustChangePassword;
        this.loginAt = LocalDateTime.now();
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
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

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public LocalDateTime getLoginAt() {
        return loginAt;
    }

    public void setLoginAt(LocalDateTime loginAt) {
        this.loginAt = loginAt;
    }
}

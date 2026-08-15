package com.tokenlimit.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * TokenLimit 服务端配置项.
 */
@Component
@ConfigurationProperties(prefix = "tokenlimit")
public class TokenLimitProperties {

    /** Redis key 前缀 */
    private String redisPrefix = "tokenlimit:quota";
    /** check 上下文保留时间（秒），report 需在此时间内上报 */
    private long checkContextTtlSeconds = 3600;
    /** 单次预估 token 上限 */
    private long maxEstimatedTokens = 1000000;
    /** 预估值与真实值偏差告警阈值（默认 50%） */
    private double anomalyDeviationThreshold = 0.5;

    /** 管理端登录账号 */
    private Admin admin = new Admin();

    /** 会话有效期（秒），滑动续期，默认 30 分钟 */
    private long sessionTtlSeconds = 1800;
    /** 同一账号允许的最大并发会话数，0 表示不限制，默认 1（单会话） */
    private int maxSessionsPerUser = 1;
    /** 登录失败达到该次数后锁定 */
    private int loginMaxFails = 5;
    /** 登录失败锁定时间（秒），默认 30 分钟 */
    private long loginLockSeconds = 1800;

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    public String getRedisPrefix() {
        return redisPrefix;
    }

    public void setRedisPrefix(String redisPrefix) {
        this.redisPrefix = redisPrefix;
    }

    public long getCheckContextTtlSeconds() {
        return checkContextTtlSeconds;
    }

    public void setCheckContextTtlSeconds(long checkContextTtlSeconds) {
        this.checkContextTtlSeconds = checkContextTtlSeconds;
    }

    public double getAnomalyDeviationThreshold() {
        return anomalyDeviationThreshold;
    }

    public void setAnomalyDeviationThreshold(double anomalyDeviationThreshold) {
        this.anomalyDeviationThreshold = anomalyDeviationThreshold;
    }

    public long getMaxEstimatedTokens() {
        return maxEstimatedTokens;
    }

    public void setMaxEstimatedTokens(long maxEstimatedTokens) {
        this.maxEstimatedTokens = maxEstimatedTokens;
    }

    public long getSessionTtlSeconds() {
        return sessionTtlSeconds;
    }

    public void setSessionTtlSeconds(long sessionTtlSeconds) {
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    public int getMaxSessionsPerUser() {
        return maxSessionsPerUser;
    }

    public void setMaxSessionsPerUser(int maxSessionsPerUser) {
        this.maxSessionsPerUser = maxSessionsPerUser;
    }

    public int getLoginMaxFails() {
        return loginMaxFails;
    }

    public void setLoginMaxFails(int loginMaxFails) {
        this.loginMaxFails = loginMaxFails;
    }

    public long getLoginLockSeconds() {
        return loginLockSeconds;
    }

    public void setLoginLockSeconds(long loginLockSeconds) {
        this.loginLockSeconds = loginLockSeconds;
    }

    /**
     * 管理端登录配置.
     */
    public static class Admin {
        private String username = "admin";
        private String password = "admin123";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}

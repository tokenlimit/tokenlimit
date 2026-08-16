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

    /** Admin 端 JWT 配置（无状态会话） */
    private Jwt jwt = new Jwt();
    /** 登录失败达到该次数后锁定 */
    private int loginMaxFails = 5;
    /** 登录失败锁定时间（秒），默认 30 分钟 */
    private long loginLockSeconds = 1800;
    /** API Key secret 哈希服务端密钥（pepper）：HMAC-SHA256 防离线碰撞，生产环境务必覆盖默认值 */
    private String hashPepper = "tokenlimit-dev-only-hash-pepper-change-me-in-production";
    /** Redis 故障降级（可用性优先）：true 时 Redis 异常按默认值放行，false 时抛出 */
    private boolean redisFallbackEnabled = true;
    /** OpenAI Compatible 网关接口级限流 */
    private RateLimit rateLimit = new RateLimit();
    /** 上游 HTTP 客户端（连接池由 JVM 统一管理，配置化超时） */
    private UpstreamHttp upstreamHttp = new UpstreamHttp();

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

    /**
     * @deprecated 自 v1.4 起 Admin 会话迁移为无状态 JWT，会话 TTL 配置不再生效；
     * 保留仅用于回退参考（旧 Redis 会话代码 {@code AuthSession} 编译依赖）。
     */
    @Deprecated
    public long getSessionTtlSeconds() {
        return 1800;
    }

    /**
     * @deprecated 同上，旧 Redis 会话代码回退参考保留。
     */
    @Deprecated
    public int getMaxSessionsPerUser() {
        return 1;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
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

    public String getHashPepper() {
        return hashPepper;
    }

    public void setHashPepper(String hashPepper) {
        this.hashPepper = hashPepper;
    }

    public boolean isRedisFallbackEnabled() {
        return redisFallbackEnabled;
    }

    public void setRedisFallbackEnabled(boolean redisFallbackEnabled) {
        this.redisFallbackEnabled = redisFallbackEnabled;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public UpstreamHttp getUpstreamHttp() {
        return upstreamHttp;
    }

    public void setUpstreamHttp(UpstreamHttp upstreamHttp) {
        this.upstreamHttp = upstreamHttp;
    }

    /**
     * OpenAI Compatible 网关接口级限流（Redis 固定窗口，多实例共享）.
     */
    public static class RateLimit {
        /** 是否启用（默认关闭，开启后按 API Key 限流） */
        private boolean enabled = false;
        /** 每 API Key 每秒最大请求数 */
        private int perKeyQps = 10;
        /** 窗口时长（秒） */
        private int windowSeconds = 1;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPerKeyQps() {
            return perKeyQps;
        }

        public void setPerKeyQps(int perKeyQps) {
            this.perKeyQps = perKeyQps;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }

    /**
     * 上游 HTTP 客户端配置（Apache HttpClient 5 连接池，见设计文档 §5.1）.
     */
    public static class UpstreamHttp {
        /** 连接超时（秒） */
        private long connectTimeoutSeconds = 15;
        /** 上游请求超时（秒），流式接口同样适用（超时后中断并结算） */
        private long requestTimeoutSeconds = 300;
        /** 连接池最大总连接数（设计文档建议 2000+，流式为长连接） */
        private int maxConnections = 2000;
        /** 单路由（单个上游域名）最大连接数 */
        private int maxConnectionsPerRoute = 500;
        /** 空闲连接回收时间（秒），防止复用到死连接 */
        private long idleEvictSeconds = 30;

        public long getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(long connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public long getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(long requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }

        public int getMaxConnections() {
            return maxConnections;
        }

        public void setMaxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
        }

        public int getMaxConnectionsPerRoute() {
            return maxConnectionsPerRoute;
        }

        public void setMaxConnectionsPerRoute(int maxConnectionsPerRoute) {
            this.maxConnectionsPerRoute = maxConnectionsPerRoute;
        }

        public long getIdleEvictSeconds() {
            return idleEvictSeconds;
        }

        public void setIdleEvictSeconds(long idleEvictSeconds) {
            this.idleEvictSeconds = idleEvictSeconds;
        }
    }

    /**
     * Admin 端 JWT 配置（无状态，无 Redis 会话）.
     */
    public static class Jwt {
        /** HS256 签名密钥，须 ≥ 32 字节；支持 Base64 或纯文本，默认值仅供本地开发 */
        private String secret = "tokenlimit-dev-only-secret-key-change-me-in-production";
        /** 签发者 */
        private String issuer = "tokenlimit-server";
        /** 令牌有效期（秒），默认 8 小时 */
        private long expireSeconds = 28800;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public long getExpireSeconds() {
            return expireSeconds;
        }

        public void setExpireSeconds(long expireSeconds) {
            this.expireSeconds = expireSeconds;
        }
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

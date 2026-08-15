package com.tokenlimit.client;

/**
 * 客户端配置.
 */
public class TokenLimitConfig {

    /** 服务端地址，如 http://127.0.0.1:8080 */
    private final String baseUrl;
    /** API Key access key（tl_&lt;ns&gt;_ak_xxx），通过 Authorization: Bearer 传递 */
    private final String apiKey;
    /** API Key secret，用于双向校验，与 access key 组合为 Bearer &lt;access_key&gt;:&lt;secret&gt; */
    private final String secret;
    /** 连接超时（毫秒） */
    private final long connectTimeoutMs;
    /** 读取超时（毫秒） */
    private final long readTimeoutMs;

    private TokenLimitConfig(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.apiKey = builder.apiKey;
        this.secret = builder.secret;
        this.connectTimeoutMs = builder.connectTimeoutMs;
        this.readTimeoutMs = builder.readTimeoutMs;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getSecret() {
        return secret;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    public static class Builder {
        private final String baseUrl;
        private String apiKey;
        private String secret;
        private long connectTimeoutMs = 2000;
        private long readTimeoutMs = 5000;

        Builder(String baseUrl) {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl 不能为空");
            }
            this.baseUrl = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1)
                    : baseUrl;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder secret(String secret) {
            this.secret = secret;
            return this;
        }

        public Builder connectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public Builder readTimeoutMs(long readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
            return this;
        }

        public TokenLimitConfig build() {
            return new TokenLimitConfig(this);
        }
    }
}

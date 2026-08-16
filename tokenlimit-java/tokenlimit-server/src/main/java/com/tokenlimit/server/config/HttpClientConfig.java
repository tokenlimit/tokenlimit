package com.tokenlimit.server.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.impl.DefaultConnectionReuseStrategy;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.util.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 上游 HTTP 客户端配置（Apache HttpClient 5，显式连接池）.
 * <p>对应设计文档 §5.1「HTTP 连接池配置（防延迟爆炸）」：</p>
 * <ul>
 *   <li><b>最大连接数</b>：流式请求是长连接，{@code MaxConnTotal} / {@code MaxConnPerRoute}
 *       必须足够大（默认 2000 / 500），否则高并发下连接池打满导致延迟爆炸。</li>
 *   <li><b>空闲回收</b>：{@code evictIdleConnections(30s)} + {@code evictExpiredConnections()}，
 *       防止复用到已被上游关闭的死连接。</li>
 *   <li><b>连接复用</b>：keep-alive 复用，复用 TCP/TLS 握手，降低首 Token 延迟。</li>
 * </ul>
 */
@Configuration
public class HttpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpClientConfig.class);

    @Bean(destroyMethod = "close")
    public CloseableHttpClient upstreamHttpClient(TokenLimitProperties properties) {
        TokenLimitProperties.UpstreamHttp http = properties.getUpstreamHttp();

        PoolingHttpClientConnectionManager connectionManager = buildConnectionManager(http);

        RequestConfig requestConfig = RequestConfig.custom()
                // 连接超时已由下方 ConnectionConfig（连接管理器）统一配置，此处已废弃（5.2+ 不再生效）
                .setResponseTimeout(Timeout.ofSeconds(http.getRequestTimeoutSeconds()))
                .setConnectionRequestTimeout(Timeout.ofSeconds(http.getConnectTimeoutSeconds()))
                .build();

        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                // 连接复用（keep-alive），避免每次请求重建连接
                .setConnectionReuseStrategy(DefaultConnectionReuseStrategy.INSTANCE)
                // 空闲回收：主动驱逐超过阈值的空闲连接（设计文档 §5.1）
                .evictIdleConnections(TimeValue.ofSeconds(http.getIdleEvictSeconds()))
                // 驱逐已过期连接，防止复用死连接
                .evictExpiredConnections()
                .disableRedirectHandling()
                .build();

        log.info("上游 HTTP 连接池初始化: maxTotal={}, maxPerRoute={}, idleEvict={}s, connectTimeout={}s",
                http.getMaxConnections(), http.getMaxConnectionsPerRoute(),
                http.getIdleEvictSeconds(), http.getConnectTimeoutSeconds());
        return client;
    }

    /**
     * 构建连接池化的 ConnectionManager（显式 MaxConnTotal / MaxConnPerRoute）.
     */
    private PoolingHttpClientConnectionManager buildConnectionManager(TokenLimitProperties.UpstreamHttp http) {
        SocketConfig socketConfig = SocketConfig.custom()
                .setSoTimeout(Timeout.ofSeconds(http.getConnectTimeoutSeconds()))
                .build();

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(http.getConnectTimeoutSeconds()))
                .setSocketTimeout(Timeout.ofSeconds(http.getRequestTimeoutSeconds()))
                // 空闲连接复用前的校验时间，防止死连接
                .setValidateAfterInactivity(TimeValue.ofSeconds(Math.max(1, http.getIdleEvictSeconds())))
                .setTimeToLive(TimeValue.ofMinutes(10))
                .build();

        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultSocketConfig(socketConfig)
                .setDefaultConnectionConfig(connectionConfig)
                .setMaxConnTotal(http.getMaxConnections())
                .setMaxConnPerRoute(http.getMaxConnectionsPerRoute())
                .build();
        return connectionManager;
    }
}

package com.tokenlimit.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 上游 HTTP 客户端（显式配置）.
 * <p>使用 JDK {@link HttpClient}：连接池由 JVM 统一管理（默认 keep-alive 复用），
 * 叠加虚拟线程（{@code spring.threads.virtual.enabled}）可支撑高并发 IO 密集转发。
 * 连接超时/请求超时由 {@code tokenlimit.http.*} 配置化，避免散落魔法值。</p>
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient upstreamHttpClient(TokenLimitProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(properties.getHttp().getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}

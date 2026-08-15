package com.tokenlimit.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenlimit.common.api.Result;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 基于 JDK HttpClient 的 HTTP 传输层，负责 JSON 序列化与统一结果解析.
 * <p>PRD V2.0：请求携带 {@code Authorization: Bearer <access_key>:<secret>}（双向校验）。
 * 若未配置 secret，则仅发送 {@code Bearer <access_key>}。</p>
 */
public class HttpTransport {

    private final TokenLimitConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpTransport(TokenLimitConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 发送 POST JSON 请求并解析为 Result&lt;T&gt;.
     */
    public <T> T post(String path, Object request, Class<T> dataClass) {
        String url = config.getBaseUrl() + path;
        String body;
        try {
            body = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new TokenLimitException("序列化请求失败", e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
                .header("Content-Type", "application/json");
        if (StringUtils.hasText(config.getApiKey())) {
            String credential = StringUtils.hasText(config.getSecret())
                    ? config.getApiKey() + ":" + config.getSecret()
                    : config.getApiKey();
            builder.header("Authorization", "Bearer " + credential);
        }
        HttpRequest httpRequest = builder
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        String responseBody;
        try {
            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());
            responseBody = response.body();
        } catch (Exception e) {
            throw new TokenLimitException("请求 TokenLimit Server 失败: " + url, e);
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            boolean success = root.path("success").asBoolean();
            int code = root.path("code").asInt();
            String message = root.path("message").asText();

            if (!success) {
                throw new TokenLimitException(code, "服务端返回错误: " + message);
            }
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return null;
            }
            return objectMapper.treeToValue(data, dataClass);
        } catch (TokenLimitException e) {
            throw e;
        } catch (Exception e) {
            throw new TokenLimitException("解析响应失败: " + responseBody, e);
        }
    }
}

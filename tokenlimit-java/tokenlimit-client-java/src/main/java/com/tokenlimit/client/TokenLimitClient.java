package com.tokenlimit.client;

import com.tokenlimit.common.dto.CheckRequest;
import com.tokenlimit.common.dto.CheckResult;
import com.tokenlimit.common.dto.ReportResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TokenLimit 客户端 SDK（PRD V2.0，Bearer api_key 鉴权）.
 *
 * <pre>{@code
 * TokenLimitClient client = new TokenLimitClient(
 *         TokenLimitConfig.builder("http://127.0.0.1:8080")
 *                 .apiKey("tl_prod_ak_xxxx")
 *                 .secret("your-secret")
 *                 .build());
 *
 * CheckResult result = client.check("gpt-4o", 1000);
 * if (!result.isAllowed()) { throw new TokenLimitException(result.getReason()); }
 *
 * ReportResult report = client.report(result.getTraceId(), "gpt-4o",
 *         "OPENAI", 800, 180, 980, "SUCCESS", 1200L);
 * }</pre>
 */
public class TokenLimitClient {

    private final TokenLimitConfig config;
    private final HttpTransport transport;

    public TokenLimitClient(String baseUrl) {
        this(TokenLimitConfig.builder(baseUrl).build());
    }

    public TokenLimitClient(TokenLimitConfig config) {
        this.config = config;
        this.transport = new HttpTransport(config);
    }

    /**
     * 配额检查（客户端调用大模型前）.
     *
     * @param model           模型
     * @param estimatedTokens 预估 Token
     */
    public CheckResult check(String model, long estimatedTokens) {
        CheckRequest request = new CheckRequest(model, estimatedTokens);
        return transport.post("/api/v1/client/quota/check", request, CheckResult.class);
    }

    /**
     * 用量上报（大模型调用完成后）.
     */
    public ReportResult report(String traceId, String model, String provider,
                               long promptTokens, long completionTokens, long totalTokens,
                               String status, Long latencyMs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", traceId);
        body.put("model", model);
        body.put("provider", provider);
        body.put("promptTokens", promptTokens);
        body.put("completionTokens", completionTokens);
        body.put("totalTokens", totalTokens);
        body.put("status", status);
        if (latencyMs != null) {
            body.put("latencyMs", latencyMs);
        }
        return transport.post("/api/v1/client/usage/report", body, ReportResult.class);
    }

    public TokenLimitConfig getConfig() {
        return config;
    }
}

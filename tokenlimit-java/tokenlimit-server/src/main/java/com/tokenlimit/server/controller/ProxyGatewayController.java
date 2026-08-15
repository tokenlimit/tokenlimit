package com.tokenlimit.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.dto.CheckResult;
import com.tokenlimit.server.entity.ApiKey;
import com.tokenlimit.server.security.OpenAiResponseWriter;
import com.tokenlimit.server.security.SecurityUtils;
import com.tokenlimit.server.service.ModelPolicyService;
import com.tokenlimit.server.service.ProviderResolverService;
import com.tokenlimit.server.service.QuotaService;
import com.tokenlimit.server.service.TokenEstimationService;
import com.tokenlimit.server.service.UpstreamProxyService;
import com.tokenlimit.server.service.redis.RateLimiterService;
import com.tokenlimit.server.config.TokenLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * OpenAI Compatible Proxy 网关（PRD V5.0）.
 * <p>客户端零改造接入：{@code Authorization: Bearer <access_key>:<secret>}。</p>
 * <p>API Key 鉴权由 {@code OpenAiApiKeyAuthenticationFilter} 统一负责。</p>
 * <p>核心流程：模型策略校验 → jtokkit 预估 → 配额 check → Provider 凭证解析 → 转发上游 → 结算。</p>
 */
@Controller
@RequestMapping("/v1")
public class ProxyGatewayController {

    private static final Logger log = LoggerFactory.getLogger(ProxyGatewayController.class);

    private final QuotaService quotaService;
    private final ProviderResolverService providerResolverService;
    private final TokenEstimationService tokenEstimationService;
    private final ModelPolicyService modelPolicyService;
    private final UpstreamProxyService upstreamProxyService;
    private final RateLimiterService rateLimiterService;
    private final TokenLimitProperties properties;
    private final ObjectMapper objectMapper;

    public ProxyGatewayController(QuotaService quotaService,
                                  ProviderResolverService providerResolverService,
                                  TokenEstimationService tokenEstimationService,
                                  ModelPolicyService modelPolicyService,
                                  UpstreamProxyService upstreamProxyService,
                                  RateLimiterService rateLimiterService,
                                  TokenLimitProperties properties,
                                  ObjectMapper objectMapper) {
        this.quotaService = quotaService;
        this.providerResolverService = providerResolverService;
        this.tokenEstimationService = tokenEstimationService;
        this.modelPolicyService = modelPolicyService;
        this.upstreamProxyService = upstreamProxyService;
        this.rateLimiterService = rateLimiterService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 模型列表（OpenAI Compatible GET /v1/models）.
     */
    @GetMapping("/models")
    public void models(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            ApiKey apiKey = SecurityUtils.currentApiKey();
            if (apiKey == null) {
                writeOpenAiError(response, ErrorCode.INVALID_API_KEY);
                return;
            }

            List<String> models = modelPolicyService.allowedModels(apiKey.getTeamCode());
            ObjectNode root = objectMapper.createObjectNode();
            root.put("object", "list");
            var data = root.putArray("data");
            for (String model : models) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("id", model);
                item.put("object", "model");
                item.put("created", 0);
                item.put("owned_by", "tokenlimit");
                data.add(item);
            }
            writeRaw(response, HttpServletResponse.SC_OK, MediaType.APPLICATION_JSON_VALUE,
                    objectMapper.writeValueAsString(root));
        } catch (BusinessException e) {
            writeOpenAiError(response, e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.warn("模型列表处理异常: {}", e.getMessage());
            writeOpenAiError(response, ErrorCode.INVALID_API_KEY);
        }
    }

    /**
     * Chat Completions（OpenAI Compatible POST /v1/chat/completions）.
     */
    @PostMapping("/chat/completions")
    public void chatCompletions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleCompletion(request, response, "chat/completions");
    }

    /**
     * Embeddings（OpenAI Compatible POST /v1/embeddings）.
     */
    @PostMapping("/embeddings")
    public void embeddings(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleCompletion(request, response, "embeddings");
    }

    /**
     * 通用处理流程.
     */
    private void handleCompletion(HttpServletRequest request, HttpServletResponse response,
                                  String upstreamPath) throws IOException {
        long startTime = System.currentTimeMillis();
        
        // 1. 读取请求体
        String body = upstreamProxyService.readBody(request.getInputStream());
        
        // 2. 获取认证信息
        ApiKey apiKey = SecurityUtils.currentApiKey();
        String[] credential = SecurityUtils.currentApiKeyCredential();
        if (apiKey == null || credential == null) {
            writeOpenAiError(response, ErrorCode.INVALID_API_KEY);
            return;
        }

        // 3. 限流检查
        if (rateLimiterService.isEnabled()) {
            int limit = properties.getRateLimit().getPerKeyQps();
            long windowMillis = properties.getRateLimit().getWindowSeconds() * 1000L;
            if (!rateLimiterService.tryAcquire(apiKey.getKeyId(), limit, windowMillis)) {
                writeOpenAiError(response, 429, "RATE_LIMIT_EXCEEDED", "请求过于频繁，请稍后重试");
                return;
            }
        }

        // 4. 解析请求
        JsonNode requestJson;
        try {
            requestJson = objectMapper.readTree(body);
        } catch (Exception e) {
            writeOpenAiError(response, 400, "invalid_request_error", "请求体不是合法 JSON");
            return;
        }
        String model = requestJson.path("model").asText(null);
        if (!StringUtils.hasText(model)) {
            writeOpenAiError(response, 400, "invalid_request_error", "model 不能为空");
            return;
        }
        boolean stream = requestJson.path("stream").asBoolean(false);

        // 5. 模型策略校验
        try {
            modelPolicyService.assertModelAllowed(apiKey.getTeamCode(), model);
        } catch (BusinessException e) {
            writeOpenAiError(response, e.getCode(), e.getMessage());
            return;
        }

        // 6. Token 预估
        long estPrompt = tokenEstimationService.estimatePromptTokens(model, requestJson);

        // 7. 配额检查
        CheckResult check;
        try {
            check = quotaService.check(credential[0], credential[1], model, estPrompt, 0, estPrompt);
        } catch (BusinessException e) {
            writeOpenAiError(response, e.getCode(), e.getMessage());
            return;
        }
        if (!check.isAllowed()) {
            writeDenied(response, check.getReason(), check.getMessage());
            return;
        }
        String traceId = check.getTraceId();
        log.debug("[{}] 配额检查通过, model={}, estPrompt={}", traceId, model, estPrompt);

        // 8. 解析 Provider 凭证
        ProviderResolverService.ResolvedCredential resolved;
        try {
            resolved = providerResolverService.resolve(apiKey.getTeamCode(), model);
        } catch (BusinessException e) {
            writeOpenAiError(response, e.getCode(), e.getMessage());
            return;
        }

        // 9. 构建上游请求
        String upstreamBody = body;
        if (stream) {
            try {
                upstreamBody = upstreamProxyService.injectStreamOptions(body);
            } catch (Exception e) {
                log.warn("[{}] 注入 stream_options 失败，使用原始请求体", traceId, e);
            }
        }
        String upstreamUrl = upstreamProxyService.buildUpstreamUrl(
                resolved.getApiBaseUrl(), resolved.getProvider(), upstreamPath);
        HttpRequest upstreamRequest = upstreamProxyService.buildRequest(upstreamUrl, upstreamBody, resolved.getApiKey());

        // 10. 转发并处理响应
        try {
            HttpResponse<InputStream> upstreamResponse = upstreamProxyService.send(upstreamRequest);
            
            if (upstreamResponse.statusCode() >= 400) {
                // 上游错误
                String err = upstreamProxyService.readErrorResponse(upstreamResponse);
                settle(credential, traceId, model, resolved.getProvider(), 0, 0, 0,
                        "ERROR", estPrompt, 0, estPrompt);
                writeRaw(response, upstreamResponse.statusCode(),
                        upstreamResponse.headers().firstValue("Content-Type").orElse(MediaType.APPLICATION_JSON_VALUE),
                        err.isEmpty() ? "{\"error\":\"upstream error\"}" : err);
                return;
            }

            if (stream) {
                handleStreamResponse(response, upstreamResponse, credential, traceId, model, 
                        resolved.getProvider(), estPrompt, startTime);
            } else {
                handleNonStreamResponse(response, upstreamResponse, credential, traceId, model,
                        resolved.getProvider(), estPrompt, startTime);
            }
        } catch (Exception e) {
            log.error("[{}] 上游转发失败", traceId, e);
            settle(credential, traceId, model, resolved.getProvider(), 0, 0, 0,
                    "ERROR", estPrompt, 0, estPrompt);
            if (!response.isCommitted()) {
                writeOpenAiError(response, ErrorCode.PROVIDER_ERROR);
            }
        }
    }

    /**
     * 处理流式响应.
     */
    private void handleStreamResponse(HttpServletResponse response, HttpResponse<InputStream> upstream,
                                      String[] credential, String traceId, String model, String provider,
                                      long estPrompt, long startTime) throws IOException {
        UpstreamProxyService.StreamResult result = upstreamProxyService.handleStreaming(response, upstream, model);
        
        long latencyMs = System.currentTimeMillis() - startTime;
        
        if (result.isHasUsage()) {
            // 使用真实 usage
            settle(credential, traceId, model, provider,
                    result.getPromptTokens(), result.getCompletionTokens(), result.getTotalTokens(),
                    "SUCCESS", estPrompt, result.getEstimatedCompletionTokens(),
                    Math.max(result.getTotalTokens(), estPrompt));
            log.debug("[{}] 流式完成, latency={}ms, tokens={}", traceId, latencyMs, result.getTotalTokens());
        } else if (result.isSuccess()) {
            // 无 usage，使用估算
            settle(credential, traceId, model, provider, 0, 0, 0, "SUCCESS",
                    estPrompt, result.getEstimatedCompletionTokens(),
                    estPrompt + result.getEstimatedCompletionTokens());
            log.debug("[{}] 流式完成(估算), latency={}ms, estTokens={}", traceId, latencyMs, 
                    result.getEstimatedCompletionTokens());
        } else {
            // 中断
            settle(credential, traceId, model, provider, 0, 0, 0, "INTERRUPTED",
                    estPrompt, result.getEstimatedCompletionTokens(),
                    estPrompt + result.getEstimatedCompletionTokens());
            log.warn("[{}] 流式中断, latency={}ms, estTokens={}", traceId, latencyMs, 
                    result.getEstimatedCompletionTokens());
        }
    }

    /**
     * 处理非流式响应.
     */
    private void handleNonStreamResponse(HttpServletResponse response, HttpResponse<InputStream> upstream,
                                         String[] credential, String traceId, String model, String provider,
                                         long estPrompt, long startTime) throws IOException {
        UpstreamProxyService.NonStreamResult result = upstreamProxyService.handleNonStreaming(response, upstream);
        
        long latencyMs = System.currentTimeMillis() - startTime;
        
        if (result.isHasUsage()) {
            settle(credential, traceId, model, provider,
                    result.getPromptTokens(), result.getCompletionTokens(), result.getTotalTokens(),
                    "SUCCESS", estPrompt, result.getCompletionTokens(),
                    Math.max(result.getTotalTokens(), estPrompt));
            log.debug("[{}] 非流式完成, latency={}ms, tokens={}", traceId, latencyMs, result.getTotalTokens());
        } else {
            settle(credential, traceId, model, provider, 0, 0, 0, "SUCCESS",
                    estPrompt, 0, estPrompt);
            log.debug("[{}] 非流式完成(无usage), latency={}ms", traceId, latencyMs);
        }
        
        writeRaw(response, result.getStatusCode(), result.getContentType(), result.getResponseBody());
    }

    /**
     * 用量结算.
     */
    private void settle(String[] credential, String traceId, String model, String provider,
                        long prompt, long completion, long total, String status,
                        long estPrompt, long estCompletion, long estTotal) {
        try {
            quotaService.report(traceId, credential[0], credential[1], model,
                    prompt, completion, total, provider, status, null,
                    estPrompt, estCompletion, estTotal);
        } catch (Exception e) {
            log.error("[{}] 用量上报失败", traceId, e);
        }
    }

    /**
     * 配额不足响应.
     */
    private void writeDenied(HttpServletResponse response, String reason, String message) {
        switch (reason == null ? "" : reason) {
            case "TEAM_QUOTA_EXCEEDED" -> writeOpenAiError(response, 429, "TEAM_QUOTA_EXCEEDED", message);
            case "USER_QUOTA_EXCEEDED" -> writeOpenAiError(response, 429, "USER_QUOTA_EXCEEDED", message);
            case "TOKEN_LIMIT_EXCEEDED" -> writeOpenAiError(response, 400, "TOKEN_LIMIT_EXCEEDED", message);
            case "TEAM_DISABLED" -> writeOpenAiError(response, 403, "TEAM_DISABLED", message);
            case "USER_DISABLED" -> writeOpenAiError(response, 403, "USER_DISABLED", message);
            default -> writeOpenAiError(response, 429, "QUOTA_EXCEEDED", message);
        }
    }

    private void writeOpenAiError(HttpServletResponse response, ErrorCode errorCode) {
        OpenAiResponseWriter.writeError(response, objectMapper, errorCode);
    }

    private void writeOpenAiError(HttpServletResponse response, int businessCode, String message) {
        OpenAiResponseWriter.writeError(response, objectMapper, businessCode, message);
    }

    private void writeOpenAiError(HttpServletResponse response, int status, String code, String message) {
        OpenAiResponseWriter.writeError(response, objectMapper, status, code, message);
    }

    private void writeRaw(HttpServletResponse response, int status, String contentType, String body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(contentType);
        response.getWriter().write(body);
    }
}

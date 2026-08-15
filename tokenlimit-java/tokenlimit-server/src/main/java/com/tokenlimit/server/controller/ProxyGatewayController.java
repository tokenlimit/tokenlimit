package com.tokenlimit.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.dto.CheckResult;
import com.tokenlimit.server.entity.ApiKey;
import com.tokenlimit.server.entity.ModelPrice;
import com.tokenlimit.server.repository.mapper.ApiKeyMapper;
import com.tokenlimit.server.repository.mapper.ModelPriceMapper;
import com.tokenlimit.server.service.ProviderResolverService;
import com.tokenlimit.server.service.QuotaService;
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
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAI Compatible Proxy 网关（PRD V4.0 / V3.1）.
 * <p>客户端零改造接入：{@code Authorization: Bearer <access_key>:<secret>}。
 * 流程：鉴权 → 配额 check（预估冻结） → Provider 凭证解析 → 转发上游 →
 * 流式/非流式透传 → usage 结算（report）。</p>
 */
@Controller
@RequestMapping("/v1")
public class ProxyGatewayController {

    private static final Logger log = LoggerFactory.getLogger(ProxyGatewayController.class);

    private final QuotaService quotaService;
    private final ProviderResolverService providerResolverService;
    private final ModelPriceMapper modelPriceMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ProxyGatewayController(QuotaService quotaService,
                                  ProviderResolverService providerResolverService,
                                  ModelPriceMapper modelPriceMapper,
                                  ApiKeyMapper apiKeyMapper,
                                  ObjectMapper objectMapper) {
        this.quotaService = quotaService;
        this.providerResolverService = providerResolverService;
        this.modelPriceMapper = modelPriceMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * 模型列表（OpenAI Compatible GET /v1/models）.
     */
    @GetMapping("/models")
    @ResponseBody
    public void models(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            ClientController.Credential credential = ClientController.extractCredential(
                    request.getHeader("Authorization"));
            if (credential.accessKey == null) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
            List<ModelPrice> prices = modelPriceMapper.selectList(
                    new LambdaQueryWrapper<ModelPrice>().eq(ModelPrice::getStatus, "ACTIVE"));
            ObjectNode root = objectMapper.createObjectNode();
            root.put("object", "list");
            com.fasterxml.jackson.databind.node.ArrayNode data = root.putArray("data");
            for (ModelPrice price : prices) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("id", price.getModel());
                item.put("object", "model");
                item.put("created", 0);
                item.put("owned_by", price.getProvider());
                data.add(item);
            }
            writeRaw(response, HttpServletResponse.SC_OK, MediaType.APPLICATION_JSON_VALUE,
                    objectMapper.writeValueAsString(root));
        } catch (BusinessException e) {
            writeOpenAiError(response, e.getCode(), e.getMessage());
        }
    }

    /**
     * Chat Completions（OpenAI Compatible POST /v1/chat/completions）.
     * <p>支持流式（SSE）与非流式；流式优先注入 {@code stream_options.include_usage} 以获取真实 usage。</p>
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
     * 通用处理：鉴权 → check → 解析凭证 → 转发 → 结算.
     */
    private void handleCompletion(HttpServletRequest request, HttpServletResponse response,
                                  String upstreamPath) throws IOException {
        String body = readBody(request);
        ClientController.Credential credential = ClientController.extractCredential(
                request.getHeader("Authorization"));
        if (credential.accessKey == null) {
            writeOpenAiError(response, 401, "未提供有效的 API Key 凭证");
            return;
        }

        JsonNode requestJson;
        try {
            requestJson = objectMapper.readTree(body);
        } catch (Exception e) {
            writeOpenAiError(response, 400, "请求体不是合法 JSON");
            return;
        }
        String model = requestJson.path("model").asText(null);
        if (!StringUtils.hasText(model)) {
            writeOpenAiError(response, 400, "model 不能为空");
            return;
        }
        boolean stream = requestJson.path("stream").asBoolean(false);

        // 1. 配额 check：预估冻结
        long estimatedTokens = estimateTokens(requestJson, model);
        CheckResult check;
        try {
            check = quotaService.check(credential.accessKey, credential.secret, model, estimatedTokens);
        } catch (BusinessException e) {
            writeOpenAiError(response, e.getCode(), e.getMessage());
            return;
        }
        if (!check.isAllowed()) {
            writeOpenAiError(response, 429, check.getMessage() + " (" + check.getReason() + ")");
            return;
        }
        final String traceId = check.getTraceId();
        final String consumeFrom = check.getConsumeFrom();

        // 2. 解析上游凭证
        ProviderResolverService.ResolvedCredential resolved;
        try {
            resolved = providerResolverService.resolve(checkConsumedTeam(credential), model);
        } catch (BusinessException e) {
            releaseAfterFailed(response, credential, traceId, model, e);
            return;
        }

        // 3. 构造上游请求（流式注入 include_usage）
        String upstreamBody = body;
        try {
            if (stream) {
                upstreamBody = injectStreamOptions(body);
            }
        } catch (Exception e) {
            log.warn("注入 stream_options 失败，使用原始请求体", e);
        }
        String upstreamUrl = buildUpstreamUrl(resolved.getApiBaseUrl(), resolved.getProvider(), upstreamPath);
        HttpRequest upstreamRequest = HttpRequest.newBuilder()
                .uri(URI.create(upstreamUrl))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + resolved.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(upstreamBody, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<InputStream> upstream = httpClient.send(upstreamRequest,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (upstream.statusCode() >= 400) {
                // 上游错误：透传并释放冻结（按预估结算）
                String err = readFully(upstream.body());
                reportFailure(credential, traceId, model, upstream.statusCode(), err);
                writeRaw(response, upstream.statusCode(), upstream.headers().firstValue("Content-Type")
                                .orElse(MediaType.APPLICATION_JSON_VALUE),
                        err.isEmpty() ? "{\"error\":\"upstream error\"}" : err);
                return;
            }
            if (stream) {
                handleStreaming(response, upstream, credential, traceId, consumeFrom, model,
                        resolved.getProvider(), estimatedTokens);
            } else {
                handleNonStreaming(response, upstream, credential, traceId, consumeFrom, model,
                        resolved.getProvider());
            }
        } catch (Exception e) {
            log.error("上游转发失败 traceId={}", traceId, e);
            reportFailure(credential, traceId, model, 502, "upstream error: " + e.getMessage());
            if (!response.isCommitted()) {
                writeOpenAiError(response, 502, "上游转发失败: " + e.getMessage());
            }
        }
    }

    /**
     * 流式透传：边收边转，从 SSE 中提取 usage 用于结算.
     */
    private void handleStreaming(HttpServletResponse response, HttpResponse<InputStream> upstream,
                                 ClientController.Credential credential, String traceId, String consumeFrom,
                                 String model, String provider, long estimatedTokens) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/event-stream");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        AtomicLong promptTokens = new AtomicLong(0);
        AtomicLong completionTokens = new AtomicLong(0);
        AtomicLong totalTokens = new AtomicLong(0);
        AtomicBoolean hasUsage = new AtomicBoolean(false);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(upstream.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if (!"[DONE]".equals(data) && !data.isEmpty()) {
                        try {
                            JsonNode node = objectMapper.readTree(data);
                            JsonNode usage = node.path("usage");
                            if (!usage.isMissingNode() && !usage.isNull()) {
                                captureUsage(usage, promptTokens, completionTokens, totalTokens);
                                hasUsage.set(true);
                            }
                        } catch (Exception ignore) {
                            // 非 JSON 的 SSE 行直接透传
                        }
                    }
                }
                writeRawLine(response, line);
            }
            response.flushBuffer();
        } catch (IOException e) {
            log.warn("流式透传中断 traceId={}: {}", traceId, e.getMessage());
        } finally {
            // 结算：优先真实 usage，缺失时按预估结算
            if (hasUsage.get()) {
                reportSuccess(credential, traceId, model, provider, promptTokens.get(),
                        completionTokens.get(), totalTokens.get(), consumeFrom);
            } else {
                reportFailure(credential, traceId, model, 200,
                        "stream without usage, settled by estimate: " + estimatedTokens);
            }
        }
    }

    /**
     * 非流式：完整读取响应并提取 usage 结算.
     */
    private void handleNonStreaming(HttpServletResponse response, HttpResponse<InputStream> upstream,
                                    ClientController.Credential credential, String traceId, String consumeFrom,
                                    String model, String provider) {
        try {
            String respBody = readFully(upstream.body());
            long prompt = 0, completion = 0, total = 0;
            try {
                JsonNode node = objectMapper.readTree(respBody);
                JsonNode usage = node.path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    prompt = usage.path("prompt_tokens").asLong(0);
                    completion = usage.path("completion_tokens").asLong(0);
                    total = usage.path("total_tokens").asLong(0);
                }
            } catch (Exception ignore) {
            }
            reportSuccess(credential, traceId, model, provider, prompt, completion,
                    total == 0 ? prompt + completion : total, consumeFrom);
            writeRaw(response, upstream.statusCode(),
                    upstream.headers().firstValue("Content-Type").orElse(MediaType.APPLICATION_JSON_VALUE),
                    respBody);
        } catch (IOException e) {
            log.error("非流式读取失败 traceId={}", traceId, e);
            reportFailure(credential, traceId, model, 502, "read upstream failed");
            if (!response.isCommitted()) {
                writeOpenAiError(response, 502, "读取上游响应失败");
            }
        }
    }

    private void captureUsage(JsonNode usage, AtomicLong prompt, AtomicLong completion, AtomicLong total) {
        long p = usage.path("prompt_tokens").asLong(0);
        long c = usage.path("completion_tokens").asLong(0);
        long t = usage.path("total_tokens").asLong(0);
        prompt.set(Math.max(prompt.get(), p));
        completion.set(Math.max(completion.get(), c));
        total.set(Math.max(total.get(), t == 0 ? p + c : t));
    }

    private void reportSuccess(ClientController.Credential credential, String traceId, String model,
                               String provider, long prompt, long completion, long total, String consumeFrom) {
        try {
            quotaService.report(traceId, credential.accessKey, credential.secret, model,
                    prompt, completion, total, provider, "SUCCESS", null);
        } catch (Exception e) {
            log.error("用量上报失败 traceId={}", traceId, e);
        }
    }

    private void reportFailure(ClientController.Credential credential, String traceId, String model,
                               int statusCode, String detail) {
        try {
            quotaService.report(traceId, credential.accessKey, credential.secret, model,
                    0, 0, 0, null, statusCode >= 500 ? "FAILED" : "CANCELLED", null);
        } catch (Exception e) {
            log.error("失败结算 traceId={}", traceId, e);
        }
    }

    private void releaseAfterFailed(HttpServletResponse response, ClientController.Credential credential,
                                    String traceId, String model, BusinessException e) {
        log.warn("凭证解析失败，按预估释放 traceId={}: {}", traceId, e.getMessage());
        reportFailure(credential, traceId, model, 400, e.getMessage());
        writeOpenAiError(response, 400, e.getMessage());
    }

    /**
     * 从已认证的 access key 解析团队，用于定位 Provider 凭证.
     */
    private String checkConsumedTeam(ClientController.Credential credential) {
        ApiKey apiKey = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getAccessKey, credential.accessKey)
                .last("limit 1"));
        return apiKey == null ? null : apiKey.getTeamCode();
    }

    private String injectStreamOptions(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        ((ObjectNode) root).set("stream_options",
                objectMapper.createObjectNode().put("include_usage", true));
        return objectMapper.writeValueAsString(root);
    }

    private String buildUpstreamUrl(String apiBaseUrl, String provider, String path) {
        if (StringUtils.hasText(apiBaseUrl)) {
            String base = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
            return base + "/" + path;
        }
        String host = switch (provider == null ? "" : provider.toLowerCase()) {
            case "anthropic" -> "https://api.anthropic.com";
            case "deepseek" -> "https://api.deepseek.com";
            case "qwen", "dashscope" -> "https://dashscope.aliyuncs.com/compatible-mode";
            case "openai" -> "https://api.openai.com";
            default -> "https://api.openai.com";
        };
        return host + "/v1/" + path;
    }

    /**
     * 预估 token：按字符数/4 估算（取整），并乘以最小 1024 保证预扣合理.
     */
    private long estimateTokens(JsonNode requestJson, String model) {
        long base = requestJson.toString().length() / 4L;
        return Math.max(base, 1024);
    }

    private String readBody(HttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = request.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    private String readFully(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private void writeRawLine(HttpServletResponse response, String line) {
        try {
            response.getWriter().write(line);
            response.getWriter().write("\n");
            response.getWriter().flush();
        } catch (IOException e) {
            // 客户端断开
        }
    }

    private void writeRaw(HttpServletResponse response, int status, String contentType, String body)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(contentType);
        response.getWriter().write(body);
    }

    private void writeOpenAiError(HttpServletResponse response, int status, String message) {
        try {
            if (response.isCommitted()) {
                return;
            }
            ObjectNode error = objectMapper.createObjectNode();
            error.put("error", objectMapper.createObjectNode()
                    .put("message", message == null ? "unknown error" : message)
                    .put("type", status == 401 ? "authentication_error"
                            : status == 429 ? "insufficient_quota" : "invalid_request_error"));
            writeRaw(response, status, MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsString(error));
        } catch (Exception e) {
            log.warn("写出 OpenAI 错误响应失败", e);
        }
    }
}

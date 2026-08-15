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
import com.tokenlimit.server.entity.TeamModelPolicy;
import com.tokenlimit.server.enums.LlmProvider;
import com.tokenlimit.server.repository.mapper.ModelPriceMapper;
import com.tokenlimit.server.repository.mapper.TeamModelPolicyMapper;
import com.tokenlimit.server.security.OpenAiResponseWriter;
import com.tokenlimit.server.security.SecurityUtils;
import com.tokenlimit.server.service.ProviderResolverService;
import com.tokenlimit.server.service.QuotaService;
import com.tokenlimit.server.service.TokenEstimationService;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAI Compatible Proxy 网关（PRD V5.0）.
 * <p>客户端零改造接入：{@code Authorization: Bearer <access_key>:<secret>}。
 * API Key 鉴权（INVALID_API_KEY / API_KEY_DISABLED / API_KEY_EXPIRED）由
 * {@code OpenAiApiKeyAuthenticationFilter} 统一负责，认证通过后从 SecurityContext
 * 获取 {@link ApiKey} 身份与原始凭证。
 * 本 Controller 流程：Team Model Policy 模型策略校验（MODEL_NOT_ALLOWED）
 * → jtokkit 预估 → 配额 check（简单计数器，只读不预扣）
 * → Provider 凭证解析 → 转发上游（流式/非流式透传）
 * → report（厂商 usage 优先，缺失按预估结算；含异常计费检测）。</p>
 */
@Controller
@RequestMapping("/v1")
public class ProxyGatewayController {

    private static final Logger log = LoggerFactory.getLogger(ProxyGatewayController.class);

    private final QuotaService quotaService;
    private final ProviderResolverService providerResolverService;
    private final TokenEstimationService tokenEstimationService;
    private final ModelPriceMapper modelPriceMapper;
    private final TeamModelPolicyMapper teamModelPolicyMapper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ProxyGatewayController(QuotaService quotaService,
                                  ProviderResolverService providerResolverService,
                                  TokenEstimationService tokenEstimationService,
                                  ModelPriceMapper modelPriceMapper,
                                  TeamModelPolicyMapper teamModelPolicyMapper,
                                  ObjectMapper objectMapper) {
        this.quotaService = quotaService;
        this.providerResolverService = providerResolverService;
        this.tokenEstimationService = tokenEstimationService;
        this.modelPriceMapper = modelPriceMapper;
        this.teamModelPolicyMapper = teamModelPolicyMapper;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * 模型列表（OpenAI Compatible GET /v1/models）.
     * <p>V5：按 API Key 所属 Team 返回可用模型；Team 未启用模型白名单时返回全部已启用模型。</p>
     */
    @GetMapping("/models")
    public void models(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // API Key 已由 OpenAiApiKeyAuthenticationFilter 认证并注入 SecurityContext
            ApiKey apiKey = SecurityUtils.currentApiKey();
            if (apiKey == null) {
                writeOpenAiError(response, ErrorCode.INVALID_API_KEY);
                return;
            }

            List<String> models = allowedModels(apiKey.getTeamCode());
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
     * 通用处理（PRD V5.0 数据面流程）.
     */
    private void handleCompletion(HttpServletRequest request, HttpServletResponse response,
                                  String upstreamPath) throws IOException {
        String body = readBody(request);
        // 1. API Key 已由 OpenAiApiKeyAuthenticationFilter 认证并注入 SecurityContext（此处不再重复鉴权）
        ApiKey apiKey = SecurityUtils.currentApiKey();
        String[] credential = SecurityUtils.currentApiKeyCredential();
        if (apiKey == null || credential == null) {
            writeOpenAiError(response, ErrorCode.INVALID_API_KEY);
            return;
        }

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

        // 2. Team Model Policy 模型策略校验（MODEL_NOT_ALLOWED）
        try {
            assertModelAllowed(apiKey.getTeamCode(), model);
        } catch (BusinessException e) {
            writeOpenAiError(response, e.getCode(), e.getMessage());
            return;
        }

        // 3. jtokkit 预估（请求发出时估算 prompt_tokens；completion 未知，结算时再补）
        long estPrompt = tokenEstimationService.estimatePromptTokens(model, requestJson);

        // 4. 配额 check：简单计数器，只读 Redis used，不预扣
        CheckResult check;
        try {
            check = quotaService.check(credential[0], credential[1], model,
                    estPrompt, 0, estPrompt);
        } catch (BusinessException e) {
            writeOpenAiError(response, e.getCode(), e.getMessage());
            return;
        }
        if (!check.isAllowed()) {
            writeDenied(response, check.getReason(), check.getMessage());
            return;
        }
        final String traceId = check.getTraceId();

        // 5. 解析上游 Provider 凭证（Team 专属 → GLOBAL → PROVIDER_NOT_FOUND）
        ProviderResolverService.ResolvedCredential resolved;
        try {
            resolved = providerResolverService.resolve(apiKey.getTeamCode(), model);
        } catch (BusinessException e) {
            writeOpenAiError(response, e.getCode(), e.getMessage());
            return;
        }

        // 6. 构造上游请求（流式注入 include_usage）
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

        // 7. 转发上游并结算
        long start = System.currentTimeMillis();
        try {
            HttpResponse<InputStream> upstream = httpClient.send(upstreamRequest,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (upstream.statusCode() >= 400) {
                // 上游错误：透传错误体，按预估结算（ESTIMATED / ERROR）
                String err = readFully(upstream.body());
                settle(credential, traceId, model, resolved.getProvider(), 0, 0, 0,
                        "ERROR", estPrompt, 0, estPrompt);
                writeRaw(response, upstream.statusCode(), upstream.headers().firstValue("Content-Type")
                                .orElse(MediaType.APPLICATION_JSON_VALUE),
                        err.isEmpty() ? "{\"error\":\"upstream error\"}" : err);
                return;
            }
            long latencyMs = System.currentTimeMillis() - start;
            if (stream) {
                handleStreaming(response, upstream, credential, traceId, model,
                        resolved.getProvider(), estPrompt, latencyMs);
            } else {
                handleNonStreaming(response, upstream, credential, traceId, model,
                        resolved.getProvider(), estPrompt, latencyMs);
            }
        } catch (Exception e) {
            log.error("上游转发失败 traceId={}", traceId, e);
            settle(credential, traceId, model, resolved.getProvider(), 0, 0, 0,
                    "ERROR", estPrompt, 0, estPrompt);
            if (!response.isCommitted()) {
                writeOpenAiError(response, ErrorCode.PROVIDER_ERROR);
            }
        }
    }

    /**
     * 流式透传：边收边转，从 SSE 中提取 usage 并累计 content 用于中断估算.
     */
    private void handleStreaming(HttpServletResponse response, HttpResponse<InputStream> upstream,
                                 String[] credential, String traceId, String model, String provider,
                                 long estPrompt, long latencyMs) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/event-stream");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        AtomicLong promptTokens = new AtomicLong(0);
        AtomicLong completionTokens = new AtomicLong(0);
        AtomicLong totalTokens = new AtomicLong(0);
        AtomicBoolean hasUsage = new AtomicBoolean(false);
        StringBuilder completionText = new StringBuilder();

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
                            // 累计 delta.content 用于中断/缺失 usage 时的估算
                            JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                            if (delta.isTextual()) {
                                completionText.append(delta.asText());
                            }
                        } catch (Exception ignore) {
                            // 非 JSON 的 SSE 行直接透传
                        }
                    }
                }
                writeRawLine(response, line);
            }
            response.flushBuffer();
            // 正常完成：优先真实 usage，缺失时按预估结算（ESTIMATED / SUCCESS）
            long estCompletion = tokenEstimationService.estimateCompletionTokens(model, completionText.toString());
            if (hasUsage.get()) {
                settle(credential, traceId, model, provider, promptTokens.get(),
                        completionTokens.get(), totalTokens.get(), "SUCCESS",
                        estPrompt, estCompletion, Math.max(totalTokens.get(), estPrompt));
            } else {
                settle(credential, traceId, model, provider, 0, 0, 0, "SUCCESS",
                        estPrompt, estCompletion, estPrompt + estCompletion);
            }
        } catch (IOException e) {
            log.warn("流式透传中断 traceId={}: {}", traceId, e.getMessage());
            // 客户端断开：按已转发内容估算结算（ESTIMATED / INTERRUPTED）
            long estCompletion = tokenEstimationService.estimateCompletionTokens(model, completionText.toString());
            settle(credential, traceId, model, provider, 0, 0, 0, "INTERRUPTED",
                    estPrompt, estCompletion, estPrompt + estCompletion);
        }
    }

    /**
     * 非流式：完整读取响应并提取 usage 结算.
     */
    private void handleNonStreaming(HttpServletResponse response, HttpResponse<InputStream> upstream,
                                    String[] credential, String traceId, String model, String provider,
                                    long estPrompt, long latencyMs) {
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
            if (total > 0) {
                settle(credential, traceId, model, provider, prompt, completion,
                        total == 0 ? prompt + completion : total, "SUCCESS",
                        estPrompt, completion, Math.max(total, estPrompt));
            } else {
                // 厂商未返回 usage：按预估结算（ESTIMATED / SUCCESS）
                settle(credential, traceId, model, provider, 0, 0, 0, "SUCCESS",
                        estPrompt, 0, estPrompt);
            }
            writeRaw(response, upstream.statusCode(),
                    upstream.headers().firstValue("Content-Type").orElse(MediaType.APPLICATION_JSON_VALUE),
                    respBody);
        } catch (IOException e) {
            log.error("非流式读取失败 traceId={}", traceId, e);
            settle(credential, traceId, model, provider, 0, 0, 0, "ERROR",
                    estPrompt, 0, estPrompt);
            if (!response.isCommitted()) {
                writeOpenAiError(response, ErrorCode.PROVIDER_ERROR);
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

    /**
     * 用量上报（report，V5 签名）：携预估 + 真实值，usage_source 与异常检测由 QuotaService 判定.
     */
    private void settle(String[] credential, String traceId, String model, String provider,
                        long prompt, long completion, long total, String status,
                        long estPrompt, long estCompletion, long estTotal) {
        try {
            quotaService.report(traceId, credential[0], credential[1], model,
                    prompt, completion, total, provider, status, null,
                    estPrompt, estCompletion, estTotal);
        } catch (Exception e) {
            log.error("用量上报失败 traceId={}", traceId, e);
        }
    }

    /**
     * Team Model Policy 模型策略校验：Team 启用模型白名单时必须命中，否则 MODEL_NOT_ALLOWED.
     */
    private void assertModelAllowed(String teamCode, String model) {
        List<TeamModelPolicy> policies = teamModelPolicyMapper.selectList(
                new LambdaQueryWrapper<TeamModelPolicy>()
                        .eq(TeamModelPolicy::getTeamCode, teamCode)
                        .eq(TeamModelPolicy::getEnabled, true));
        if (policies.isEmpty()) {
            return; // 未启用模型白名单
        }
        boolean allowed = policies.stream().anyMatch(p ->
                model.equals(p.getModel()) || "*".equals(p.getModel()));
        if (!allowed) {
            throw new BusinessException(ErrorCode.MODEL_NOT_ALLOWED);
        }
    }

    /**
     * Team 可用模型列表：启用白名单时返回命中模型（* 展开），否则返回全部已启用模型.
     */
    private List<String> allowedModels(String teamCode) {
        List<TeamModelPolicy> policies = teamModelPolicyMapper.selectList(
                new LambdaQueryWrapper<TeamModelPolicy>()
                        .eq(TeamModelPolicy::getTeamCode, teamCode)
                        .eq(TeamModelPolicy::getEnabled, true));
        List<ModelPrice> prices = modelPriceMapper.selectList(
                new LambdaQueryWrapper<ModelPrice>().eq(ModelPrice::getStatus, "ENABLED"));
        if (policies.isEmpty()) {
            List<String> all = new ArrayList<>();
            for (ModelPrice price : prices) {
                all.add(price.getModel());
            }
            return all;
        }
        Set<String> allModels = new LinkedHashSet<>();
        for (ModelPrice price : prices) {
            allModels.add(price.getModel());
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (TeamModelPolicy policy : policies) {
            if ("*".equals(policy.getModel())) {
                allowed.addAll(allModels);
            } else if (StringUtils.hasText(policy.getModel())) {
                allowed.add(policy.getModel());
            }
        }
        return new ArrayList<>(allowed);
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
        // 兜底：未配置上游地址时使用已知厂商枚举的默认地址
        String base = LlmProvider.defaultBaseUrl(provider);
        if (base == null) {
            base = LlmProvider.OPENAI.getDefaultBaseUrl();
        }
        return base + "/" + path;
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

    /**
     * 配额不足响应（OpenAI Compatible，429/403/400 + 语义化 code）.
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

    /**
     * 按业务错误码写出 OpenAI 兼容错误响应（PRD V5.0 6.5 错误码映射，实现见 {@link OpenAiResponseWriter}）.
     */
    private void writeOpenAiError(HttpServletResponse response, int businessCode, String message) {
        OpenAiResponseWriter.writeError(response, objectMapper, businessCode, message);
    }

    private void writeOpenAiError(HttpServletResponse response, ErrorCode errorCode) {
        OpenAiResponseWriter.writeError(response, objectMapper, errorCode);
    }

    private void writeOpenAiError(HttpServletResponse response, int status, String code, String message) {
        OpenAiResponseWriter.writeError(response, objectMapper, status, code, message);
    }
}

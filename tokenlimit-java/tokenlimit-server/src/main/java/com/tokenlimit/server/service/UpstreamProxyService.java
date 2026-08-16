package com.tokenlimit.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tokenlimit.server.enums.LlmProvider;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 上游代理服务：处理 HTTP 转发、流式/非流式响应、token 估算与结算.
 * <p>使用 Apache HttpClient 5 连接池化客户端（见 {@code HttpClientConfig}），
 * 连接复用 + 空闲回收，满足设计文档 §5.1 要求。</p>
 * <p>流式响应采用分块 token 估算，避免 completion 全文驻留内存导致 OOM。</p>
 */
@Service
public class UpstreamProxyService {

    private static final Logger log = LoggerFactory.getLogger(UpstreamProxyService.class);
    private static final int TOKEN_ESTIMATE_CHUNK_SIZE = 4000;

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TokenEstimationService tokenEstimationService;

    public UpstreamProxyService(CloseableHttpClient upstreamHttpClient,
                                ObjectMapper objectMapper,
                                TokenEstimationService tokenEstimationService) {
        this.httpClient = upstreamHttpClient;
        this.objectMapper = objectMapper;
        this.tokenEstimationService = tokenEstimationService;
    }

    /**
     * 构建上游请求 URL.
     */
    public String buildUpstreamUrl(String apiBaseUrl, String provider, String path) {
        if (StringUtils.hasText(apiBaseUrl)) {
            String base = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
            return base + "/" + path;
        }
        String base = LlmProvider.defaultBaseUrl(provider);
        if (base == null) {
            base = LlmProvider.OPENAI.getDefaultBaseUrl();
        }
        return base + "/" + path;
    }

    /**
     * 发送请求到上游（连接池复用，返回响应；调用方负责关闭响应释放连接）.
     */
    public CloseableHttpResponse send(String url, String body, String apiKey) throws IOException {
        HttpPost post = new HttpPost(url);
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Authorization", "Bearer " + apiKey);
        post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        return httpClient.execute(post);
    }

    /**
     * 注入流式 stream_options.include_usage.
     */
    public String injectStreamOptions(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        ((ObjectNode) root).set("stream_options",
                objectMapper.createObjectNode().put("include_usage", true));
        return objectMapper.writeValueAsString(root);
    }

    /**
     * 处理流式响应：边收边转，分块估算 token，避免内存膨胀.
     *
     * @return 结算结果（usage 信息）
     */
    public StreamResult handleStreaming(HttpServletResponse response, CloseableHttpResponse upstream,
                                        String model) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/event-stream");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        AtomicLong promptTokens = new AtomicLong(0);
        AtomicLong completionTokens = new AtomicLong(0);
        AtomicLong totalTokens = new AtomicLong(0);
        AtomicBoolean hasUsage = new AtomicBoolean(false);

        // 分块估算：累积文本，达到阈值时估算并清空
        StringBuilder chunkBuffer = new StringBuilder();
        AtomicLong estimatedCompletionTokens = new AtomicLong(0);

        InputStream content = readEntity(upstream.getEntity());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(content, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if (!"[DONE]".equals(data) && !data.isEmpty()) {
                        try {
                            JsonNode node = objectMapper.readTree(data);
                            // 提取真实 usage（如果有）
                            JsonNode usage = node.path("usage");
                            if (!usage.isMissingNode() && !usage.isNull()) {
                                captureUsage(usage, promptTokens, completionTokens, totalTokens);
                                hasUsage.set(true);
                            }
                            // 累积 delta.content 用于分块估算
                            JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                            if (delta.isTextual()) {
                                chunkBuffer.append(delta.asText());
                                // 达到阈值时估算并清空
                                if (chunkBuffer.length() >= TOKEN_ESTIMATE_CHUNK_SIZE) {
                                    estimatedCompletionTokens.addAndGet(
                                            tokenEstimationService.estimateTokens(model, chunkBuffer.toString()));
                                    chunkBuffer.setLength(0);
                                }
                            }
                        } catch (Exception ignore) {
                            // 非 JSON 的 SSE 行直接透传
                        }
                    }
                }
                writeRawLine(response, line);
            }
            response.flushBuffer();

            // 处理剩余 buffer
            if (!chunkBuffer.isEmpty()) {
                estimatedCompletionTokens.addAndGet(
                        tokenEstimationService.estimateTokens(model, chunkBuffer.toString()));
            }

            StreamResult result = new StreamResult();
            result.setSuccess(true);
            result.setHasUsage(hasUsage.get());
            result.setPromptTokens(promptTokens.get());
            result.setCompletionTokens(completionTokens.get());
            result.setTotalTokens(totalTokens.get());
            result.setEstimatedCompletionTokens(estimatedCompletionTokens.get());
            return result;

        } catch (IOException e) {
            log.warn("流式透传中断: {}", e.getMessage());
            // 处理剩余 buffer
            if (chunkBuffer.length() > 0) {
                estimatedCompletionTokens.addAndGet(
                        tokenEstimationService.estimateTokens(model, chunkBuffer.toString()));
            }
            StreamResult result = new StreamResult();
            result.setSuccess(false);
            result.setInterrupted(true);
            result.setEstimatedCompletionTokens(estimatedCompletionTokens.get());
            return result;
        }
    }

    /**
     * 处理非流式响应：完整读取并提取 usage.
     */
    public NonStreamResult handleNonStreaming(CloseableHttpResponse upstream) throws IOException {
        String respBody = readEntityToString(upstream.getEntity());

        NonStreamResult result = new NonStreamResult();
        result.setResponseBody(respBody);
        result.setStatusCode(upstream.getCode());

        try {
            JsonNode node = objectMapper.readTree(respBody);
            JsonNode usage = node.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                result.setPromptTokens(usage.path("prompt_tokens").asLong(0));
                result.setCompletionTokens(usage.path("completion_tokens").asLong(0));
                result.setTotalTokens(usage.path("total_tokens").asLong(0));
                result.setHasUsage(true);
            }
        } catch (Exception ignore) {
            // 解析失败，按无 usage 处理
        }

        return result;
    }

    /**
     * 读取上游错误响应体.
     */
    public String readErrorResponse(CloseableHttpResponse upstream) throws IOException {
        return readEntityToString(upstream.getEntity());
    }

    /**
     * 读取请求体.
     */
    public String readBody(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private InputStream readEntity(HttpEntity entity) throws IOException {
        return entity == null ? InputStream.nullInputStream() : entity.getContent();
    }

    private String readEntityToString(HttpEntity entity) throws IOException {
        return readFully(readEntity(entity));
    }

    private void captureUsage(JsonNode usage, AtomicLong prompt, AtomicLong completion, AtomicLong total) {
        long p = usage.path("prompt_tokens").asLong(0);
        long c = usage.path("completion_tokens").asLong(0);
        long t = usage.path("total_tokens").asLong(0);
        prompt.set(Math.max(prompt.get(), p));
        completion.set(Math.max(completion.get(), c));
        total.set(Math.max(total.get(), t == 0 ? p + c : t));
    }

    private String readFully(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
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
            // 客户端断开，忽略
        }
    }

    /**
     * 流式响应结果.
     */
    public static class StreamResult {
        private boolean success;
        private boolean interrupted;
        private boolean hasUsage;
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private long estimatedCompletionTokens;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public boolean isInterrupted() { return interrupted; }
        public void setInterrupted(boolean interrupted) { this.interrupted = interrupted; }
        public boolean isHasUsage() { return hasUsage; }
        public void setHasUsage(boolean hasUsage) { this.hasUsage = hasUsage; }
        public long getPromptTokens() { return promptTokens; }
        public void setPromptTokens(long promptTokens) { this.promptTokens = promptTokens; }
        public long getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(long completionTokens) { this.completionTokens = completionTokens; }
        public long getTotalTokens() { return totalTokens; }
        public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
        public long getEstimatedCompletionTokens() { return estimatedCompletionTokens; }
        public void setEstimatedCompletionTokens(long estimatedCompletionTokens) { this.estimatedCompletionTokens = estimatedCompletionTokens; }
    }

    /**
     * 非流式响应结果.
     */
    public static class NonStreamResult {
        private String responseBody;
        private int statusCode;
        private boolean hasUsage;
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;

        public String getResponseBody() { return responseBody; }
        public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
        public int getStatusCode() { return statusCode; }
        public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
        public boolean isHasUsage() { return hasUsage; }
        public void setHasUsage(boolean hasUsage) { this.hasUsage = hasUsage; }
        public long getPromptTokens() { return promptTokens; }
        public void setPromptTokens(long promptTokens) { this.promptTokens = promptTokens; }
        public long getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(long completionTokens) { this.completionTokens = completionTokens; }
        public long getTotalTokens() { return totalTokens; }
        public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
    }
}

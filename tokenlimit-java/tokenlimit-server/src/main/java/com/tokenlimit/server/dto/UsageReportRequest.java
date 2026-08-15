package com.tokenlimit.server.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 用量上报请求（PRD V5.0，带校验）.
 * <p>鉴权通过 HTTP 头 {@code Authorization: Bearer <access_key>:<secret>} 传递（双向校验）。</p>
 * <p>estimated 字段由网关使用 jtokkit 计算，用于 usage_log 记录与异常检测。</p>
 */
public class UsageReportRequest {

    @NotBlank(message = "traceId 不能为空")
    private String traceId;

    @NotBlank(message = "model 不能为空")
    private String model;

    @NotNull(message = "promptTokens 不能为空")
    @Min(value = 0, message = "promptTokens 不能为负数")
    private Long promptTokens;

    @NotNull(message = "completionTokens 不能为空")
    @Min(value = 0, message = "completionTokens 不能为负数")
    private Long completionTokens;

    @NotNull(message = "totalTokens 不能为空")
    @Min(value = 0, message = "totalTokens 不能为负数")
    private Long totalTokens;

    /** 调用耗时（毫秒） */
    @Min(value = 0, message = "latencyMs 不能为负数")
    private Long latencyMs;

    /** 模型供应商，例如 OPENAI / DEEPSEEK / QWEN */
    private String provider;

    /** 调用结果：SUCCESS / FAILED / INTERRUPTED / ERROR（缺省 SUCCESS） */
    private String status;

    /** 预估 prompt tokens（jtokkit），可选 */
    private Long estimatedPromptTokens;

    /** 预估 completion tokens（jtokkit），可选 */
    private Long estimatedCompletionTokens;

    /** 预估总 tokens（jtokkit），可选 */
    private Long estimatedTotalTokens;

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getEstimatedPromptTokens() {
        return estimatedPromptTokens;
    }

    public void setEstimatedPromptTokens(Long estimatedPromptTokens) {
        this.estimatedPromptTokens = estimatedPromptTokens;
    }

    public Long getEstimatedCompletionTokens() {
        return estimatedCompletionTokens;
    }

    public void setEstimatedCompletionTokens(Long estimatedCompletionTokens) {
        this.estimatedCompletionTokens = estimatedCompletionTokens;
    }

    public Long getEstimatedTotalTokens() {
        return estimatedTotalTokens;
    }

    public void setEstimatedTotalTokens(Long estimatedTotalTokens) {
        this.estimatedTotalTokens = estimatedTotalTokens;
    }
}

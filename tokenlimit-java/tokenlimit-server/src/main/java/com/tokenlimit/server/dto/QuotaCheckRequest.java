package com.tokenlimit.server.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 配额检查请求（PRD V5.0，带校验）.
 * <p>鉴权通过 HTTP 头 {@code Authorization: Bearer <access_key>:<secret>} 传递（双向校验）。</p>
 * <p>estimatedTokens 为兼容字段，等价于 estimatedTotalTokens。</p>
 */
public class QuotaCheckRequest {

    @NotBlank(message = "model 不能为空")
    private String model;

    /** 预估 prompt tokens（jtokkit），可选 */
    private Long estimatedPromptTokens;

    /** 预估 completion tokens（jtokkit），可选 */
    private Long estimatedCompletionTokens;

    /** 预估总 tokens（jtokkit），可选；兼容字段 estimatedTokens 的别名 */
    private Long estimatedTotalTokens;

    /** 兼容字段：预估总 tokens */
    private Long estimatedTokens;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
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

    public Long getEstimatedTokens() {
        return estimatedTokens;
    }

    public void setEstimatedTokens(Long estimatedTokens) {
        this.estimatedTokens = estimatedTokens;
    }
}

package com.tokenlimit.common.dto;

import java.io.Serializable;

/**
 * 配额检查请求（PRD V5.0）.
 * <p>鉴权通过 HTTP 头 {@code Authorization: Bearer <api_key>} 传递，
 * 请求体包含模型与预估 token（由网关 jtokkit 预估或客户端透传）。</p>
 */
public class CheckRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模型 */
    private String model;
    /** 预估 Prompt Token（V5.0） */
    private long estimatedPromptTokens;
    /** 预估 Completion Token（V5.0） */
    private long estimatedCompletionTokens;
    /** 预估 Total Token（V5.0） */
    private long estimatedTotalTokens;
    /** 预估 Total Token 兼容别名（等价于 estimatedTotalTokens） */
    private long estimatedTokens;

    public CheckRequest() {
    }

    public CheckRequest(String model, long estimatedTokens) {
        this.model = model;
        this.estimatedTokens = estimatedTokens;
        this.estimatedTotalTokens = estimatedTokens;
    }

    public CheckRequest(String model, long estimatedPromptTokens, long estimatedCompletionTokens, long estimatedTotalTokens) {
        this.model = model;
        this.estimatedPromptTokens = estimatedPromptTokens;
        this.estimatedCompletionTokens = estimatedCompletionTokens;
        this.estimatedTotalTokens = estimatedTotalTokens;
        this.estimatedTokens = estimatedTotalTokens;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public long getEstimatedPromptTokens() {
        return estimatedPromptTokens;
    }

    public void setEstimatedPromptTokens(long estimatedPromptTokens) {
        this.estimatedPromptTokens = estimatedPromptTokens;
    }

    public long getEstimatedCompletionTokens() {
        return estimatedCompletionTokens;
    }

    public void setEstimatedCompletionTokens(long estimatedCompletionTokens) {
        this.estimatedCompletionTokens = estimatedCompletionTokens;
    }

    public long getEstimatedTotalTokens() {
        return estimatedTotalTokens;
    }

    public void setEstimatedTotalTokens(long estimatedTotalTokens) {
        this.estimatedTotalTokens = estimatedTotalTokens;
    }

    public long getEstimatedTokens() {
        return estimatedTokens;
    }

    public void setEstimatedTokens(long estimatedTokens) {
        this.estimatedTokens = estimatedTokens;
    }
}

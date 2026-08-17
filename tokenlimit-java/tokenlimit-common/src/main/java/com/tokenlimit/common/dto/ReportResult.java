package com.tokenlimit.common.dto;

import java.io.Serializable;

/**
 * 用量上报结果.
 */
public class ReportResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否成功 */
    private boolean success;
    /** 上报后剩余 Token */
    private long remainTokens;
    /** 上报日志 ID */
    private Long logId;

    public ReportResult() {
    }

    public ReportResult(boolean success, long remainTokens, Long logId) {
        this.success = success;
        this.remainTokens = remainTokens;
        this.logId = logId;
    }

    public static ReportResult success(long remainTokens, Long logId) {
        return new ReportResult(true, remainTokens, logId);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public long getRemainTokens() {
        return remainTokens;
    }

    public void setRemainTokens(long remainTokens) {
        this.remainTokens = remainTokens;
    }

    public Long getLogId() {
        return logId;
    }

    public void setLogId(Long logId) {
        this.logId = logId;
    }
}

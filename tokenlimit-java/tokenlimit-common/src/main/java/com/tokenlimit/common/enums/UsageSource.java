package com.tokenlimit.common.enums;

/**
 * 用量来源（usage_log.usage_source）.
 * <p>PRD V5.0 8.4：标识用量数据是来自上游供应商实际返回，还是网关本地预估。</p>
 */
public enum UsageSource {

    /** 上游供应商实际返回 */
    PROVIDER,
    /** 网关本地预估（供应商未返回 usage 或调用失败） */
    ESTIMATED
}

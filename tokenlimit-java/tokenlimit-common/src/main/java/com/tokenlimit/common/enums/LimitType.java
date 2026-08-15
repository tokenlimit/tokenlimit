package com.tokenlimit.common.enums;

/**
 * 限制类型.
 */
public enum LimitType {
    /** Token 数 */
    TOKEN,
    /** 费用（元） */
    COST,
    /** 请求总数 */
    REQUEST_COUNT,
    /** 每分钟请求数 */
    RPM,
    /** 每分钟 Token 数 */
    TPM
}

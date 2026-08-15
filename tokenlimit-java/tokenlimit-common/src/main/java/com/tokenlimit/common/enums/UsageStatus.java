package com.tokenlimit.common.enums;

/**
 * 使用记录状态（PRD V5.0 8.4）.
 */
public enum UsageStatus {
    /** 调用成功 */
    SUCCESS,
    /** 调用失败（如供应商返回错误） */
    FAILED,
    /** 被取消 / 未发生实际调用 */
    CANCELLED,
    /** 流式调用被中断（客户端断开或异常中断） */
    INTERRUPTED,
    /** 系统错误 */
    ERROR
}

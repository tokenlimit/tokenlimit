package com.tokenlimit.common.api;

/**
 * 统一错误码.
 *
 * <p>错误码分两类：管理端业务码（4xxx）与网关协议错误码（PRD V5.0 6.5）。
 * 网关协议错误码使用语义化枚举名作为 {@code error.code}，并映射对应 HTTP 状态。</p>
 */
public enum ErrorCode {

    /** 成功 */
    OK(0, "ok", 200),
    /** 参数错误 */
    BAD_REQUEST(4000, "参数错误", 400),
    /** 未授权 / 非法 API Key（旧客户端码，保留兼容） */
    UNAUTHORIZED(4001, "未授权或 API Key 无效", 401),
    /** 配额规则不存在 */
    QUOTA_RULE_NOT_FOUND(4028, "配额规则不存在", 400),
    /** 配额超限（旧客户端码，保留兼容） */
    QUOTA_EXCEEDED(4029, "配额超限", 429),
    /** Trace 不存在 */
    TRACE_NOT_FOUND(4031, "Trace 不存在", 400),
    /** 资源不存在 */
    NOT_FOUND(4040, "资源不存在", 404),
    /** 服务内部错误 */
    INTERNAL_ERROR(5000, "服务内部错误", 500),

    // ==================== 网关协议错误码（PRD V5.0 6.5） ====================

    /** API Key 无效（未找到或 secret 校验失败） */
    INVALID_API_KEY(4010, "API Key 无效", 401),
    /** API Key 已被禁用 */
    API_KEY_DISABLED(4011, "API Key 已被禁用", 401),
    /** API Key 已过期 */
    API_KEY_EXPIRED(4012, "API Key 已过期", 401),
    /** 团队配额超限 */
    TEAM_QUOTA_EXCEEDED(4290, "团队配额超限", 429),
    /** 用户配额超限 */
    USER_QUOTA_EXCEEDED(4291, "用户配额超限", 429),
    /** 模型不允许 */
    MODEL_NOT_ALLOWED(4030, "模型未授权", 403),
    /** 供应商凭证未配置 */
    PROVIDER_NOT_FOUND(5001, "供应商凭证未配置", 500),
    /** 上游供应商错误 */
    PROVIDER_ERROR(5020, "上游供应商错误", 502);

    private final int code;
    private final String message;
    private final int httpStatus;

    ErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /** 对应的 HTTP 状态码 */
    public int getHttpStatus() {
        return httpStatus;
    }
}

package com.tokenlimit.server.service.quota;

/**
 * 配额拦截拒绝结果（责任链任一环节返回即拦截）.
 *
 * @param reason  错误码（TEAM_QUOTA_EXCEEDED / USER_QUOTA_EXCEEDED）
 * @param message 拒绝说明（配额超限详情）
 */
public record QuotaDenied(String reason, String message) {
}

package com.tokenlimit.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tokenlimit.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI Compatible 错误响应写入工具（/v1/** 网关层共享）.
 * <p>由 {@link OpenAiApiKeyAuthenticationFilter} 与 {@code ProxyGatewayController} 共用，
 * 保证鉴权 / 业务错误的响应结构与 PRD V5.0 6.5 错误码映射一致。</p>
 */
public final class OpenAiResponseWriter {

    private OpenAiResponseWriter() {
    }

    /**
     * 按业务错误码写出 OpenAI 兼容错误响应（4010/4011/4012/4030/4290/4291/5001/5020）.
     */
    public static void writeError(HttpServletResponse response, ObjectMapper objectMapper,
                                  int businessCode, String message) {
        switch (businessCode) {
            case 4010 -> writeError(response, objectMapper, 401, "INVALID_API_KEY", message);
            case 4011 -> writeError(response, objectMapper, 401, "API_KEY_DISABLED", message);
            case 4012 -> writeError(response, objectMapper, 401, "API_KEY_EXPIRED", message);
            case 4030 -> writeError(response, objectMapper, 403, "MODEL_NOT_ALLOWED", message);
            case 4290 -> writeError(response, objectMapper, 429, "TEAM_QUOTA_EXCEEDED", message);
            case 4291 -> writeError(response, objectMapper, 429, "USER_QUOTA_EXCEEDED", message);
            case 5001 -> writeError(response, objectMapper, 500, "PROVIDER_NOT_FOUND", message);
            case 5020 -> writeError(response, objectMapper, 502, "PROVIDER_ERROR", message);
            default -> writeError(response, objectMapper, 500, "INTERNAL_ERROR", message);
        }
    }

    /**
     * 按 {@link ErrorCode} 写出 OpenAI 兼容错误响应.
     */
    public static void writeError(HttpServletResponse response, ObjectMapper objectMapper, ErrorCode errorCode) {
        writeError(response, objectMapper, errorCode.getHttpStatus(), errorCode.name(), errorCode.getMessage());
    }

    /**
     * 直接指定 HTTP 状态码 / code / message 写出 OpenAI 兼容错误响应.
     */
    public static void writeError(HttpServletResponse response, ObjectMapper objectMapper,
                                  int status, String code, String message) {
        if (response.isCommitted()) {
            return;
        }
        try {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("error", objectMapper.createObjectNode()
                    .put("message", message == null ? "unknown error" : message)
                    .put("type", status == 401 ? "authentication_error"
                            : status == 429 ? "insufficient_quota"
                            : status == 403 ? "permission_error" : "invalid_request_error")
                    .put("code", code));
            response.setStatus(status);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(error));
        } catch (IOException e) {
            // 响应已无法写出，静默忽略
        }
    }
}

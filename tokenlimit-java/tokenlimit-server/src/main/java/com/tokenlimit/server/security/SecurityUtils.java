package com.tokenlimit.server.security;

import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.server.entity.ApiKey;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * SecurityContext 访问工具.
 * <p>Web 管理端：由 {@link JwtAuthenticationFilter} 写入的认证对象中 principal 为
 * {@link SessionInfo} 身份快照，credentials 为原始 JWT。</p>
 * <p>OpenAI Compatible 网关：由 {@link OpenAiApiKeyAuthenticationFilter} 写入的认证对象中
 * principal 为 {@link ApiKey} 身份，credentials 为原始凭证 {@code [accessKey, secret]}。</p>
 * <p>统一从这里读取当前身份，避免 Controller 重复解析 header。</p>
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 当前登录会话（未登录返回 null）.
     */
    public static SessionInfo currentSession() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionInfo)) {
            return null;
        }
        return (SessionInfo) authentication.getPrincipal();
    }

    /**
     * 当前会话原始 JWT（未登录返回 null）.
     */
    public static String currentToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getCredentials() == null) {
            return null;
        }
        Object credentials = authentication.getCredentials();
        return credentials instanceof String ? (String) credentials : null;
    }

    /**
     * 要求已登录，否则抛 401.
     */
    public static SessionInfo requireSession() {
        SessionInfo session = currentSession();
        if (session == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return session;
    }

    /**
     * 当前已认证的 API Key（OpenAI Compatible 网关；未认证返回 null）.
     */
    public static ApiKey currentApiKey() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof ApiKey)) {
            return null;
        }
        return (ApiKey) authentication.getPrincipal();
    }

    /**
     * 当前 API Key 原始凭证 {@code [accessKey, secret]}（用量上报使用；未认证返回 null）.
     */
    public static String[] currentApiKeyCredential() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getCredentials() instanceof String[])) {
            return null;
        }
        return (String[]) authentication.getCredentials();
    }
}

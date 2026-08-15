package com.tokenlimit.server.security;

import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.server.entity.ApiKey;
import com.tokenlimit.server.service.AuthSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * SecurityContext 访问工具.
 * <p>Web 会话：由 {@link TokenAuthenticationFilter} 写入的认证对象中 principal 为
 * {@link AuthSession.SessionInfo} 身份快照，credentials 为原始 Bearer token。</p>
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
    public static AuthSession.SessionInfo currentSession() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthSession.SessionInfo)) {
            return null;
        }
        return (AuthSession.SessionInfo) authentication.getPrincipal();
    }

    /**
     * 当前会话原始 token（未登录返回 null）.
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
    public static AuthSession.SessionInfo requireSession() {
        AuthSession.SessionInfo session = currentSession();
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

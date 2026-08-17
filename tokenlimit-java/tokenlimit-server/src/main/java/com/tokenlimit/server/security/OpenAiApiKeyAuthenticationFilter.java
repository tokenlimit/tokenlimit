package com.tokenlimit.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.server.entity.ApiKey;
import com.tokenlimit.server.service.QuotaService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * OpenAI Compatible API Key 认证过滤器（/v1/**，PRD V5.0 数据面）.
 * <p>从 {@code Authorization: Bearer <access_key>:<secret>} 提取 API Key 凭证，经
 * {@link QuotaService#authenticate} 校验（含 ENABLED 状态与过期校验），成功后注入
 * {@code SecurityContext}：principal 为 {@link ApiKey} 身份，credentials 为原始凭证
 * {@code [accessKey, secret]}（供 {@code ProxyGatewayController} 用量上报使用）；
 * 失败直接写出 OpenAI 兼容错误（401 INVALID_API_KEY / API_KEY_DISABLED / API_KEY_EXPIRED），
 * 不再进入 Controller。与 JWT 过滤器（Web 管理会话）职责分离。</p>
 */
@Component
public class OpenAiApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OpenAiApiKeyAuthenticationFilter.class);

    private final QuotaService quotaService;
    private final ObjectMapper objectMapper;

    public OpenAiApiKeyAuthenticationFilter(QuotaService quotaService, ObjectMapper objectMapper) {
        this.quotaService = quotaService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 仅处理 OpenAI Compatible 网关路径
        String uri = request.getRequestURI();
        return uri == null || (!uri.equals("/v1") && !uri.startsWith("/v1/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String[] credential = parseCredential(request.getHeader("Authorization"));
        if (credential == null) {
            OpenAiResponseWriter.writeError(response, objectMapper, 401, "INVALID_API_KEY",
                    "未提供有效的 API Key 凭证");
            return;
        }

        ApiKey apiKey;
        try {
            apiKey = quotaService.authenticate(credential[0], credential[1]);
        } catch (BusinessException e) {
            OpenAiResponseWriter.writeError(response, objectMapper, e.getCode(), e.getMessage());
            return;
        } catch (Exception e) {
            log.warn("API Key 认证异常: {}", e.getMessage());
            OpenAiResponseWriter.writeError(response, objectMapper, 401, "INVALID_API_KEY", "API Key 认证失败");
            return;
        }
        if (apiKey == null) {
            OpenAiResponseWriter.writeError(response, objectMapper, 401, "INVALID_API_KEY", "无效的 API Key 凭证");
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(apiKey, credential, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }

    private static String[] parseCredential(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        if (!StringUtils.hasText(token)) {
            return null;
        }
        int idx = token.indexOf(':');
        if (idx <= 0) {
            return null;
        }
        String accessKey = token.substring(0, idx).trim();
        String secret = token.substring(idx + 1).trim();
        return StringUtils.hasText(accessKey) && StringUtils.hasText(secret)
                ? new String[]{accessKey, secret} : null;
    }
}

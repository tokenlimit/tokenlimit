package com.tokenlimit.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器（无状态）.
 * <p>从 {@code Authorization: Bearer <jwt>} 提取 JWT，经 {@link JwtTokenProvider} 校验并还原
 * 身份快照（{@link SessionInfo}），注入 {@code SecurityContext}。角色以 {@code ROLE_} 前缀映射为
 * Spring Security authority（如 {@code ROLE_ADMIN}），供 {@code @PreAuthorize} 使用。</p>
 * <p>企业级行为：</p>
 * <ul>
 *   <li>认证失败不在此处抛异常，交由 {@code AuthenticationEntryPoint} 统一返回 401</li>
 *   <li>首次登录强制改密：JWT 携带 {@code mustChangePassword} 标记时，除改密/个人信息/登出外
 *       一律拒绝访问（403）</li>
 *   <li>数据面接口不参与会话认证，由独立过滤器负责：{@code /v1/**} OpenAI Compatible
 *       网关走 {@link OpenAiApiKeyAuthenticationFilter}（API Key 认证）；
 *       {@code /api/v1/client/**} 客户端数据面接口（V4 遗留）走 API Key 自校验</li>
 * </ul>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /** 强制改密状态下仍允许访问的路径 */
    private static final String[] MUST_CHANGE_PASSWORD_ALLOWED = {
            "/api/v1/admin/auth/change-password",
            "/api/v1/admin/auth/profile",
            "/api/v1/admin/auth/logout"
    };

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 数据面接口不参与 Web 会话认证：
        // - /v1/** OpenAI Compatible 网关 → OpenAiApiKeyAuthenticationFilter（API Key 认证）
        // - /api/v1/client/** 客户端数据面接口（V4 遗留）→ API Key 自校验
        String uri = request.getRequestURI();
        return uri != null && (uri.startsWith("/v1/") || uri.startsWith("/api/v1/client/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = extractBearerToken(request.getHeader("Authorization"));
        if (StringUtils.hasText(token)) {
            SessionInfo session = jwtTokenProvider.parse(token);
            if (session != null) {
                String role = StringUtils.hasText(session.getRole()) ? session.getRole() : "USER";
                List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
                // principal=身份快照, credentials=原始 JWT（前端随后续请求带回）
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(session, token, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);

                // 强制改密拦截：仅放行改密/个人信息/登出，防止首次登录后绕过改密
                if (session.isMustChangePassword() && !isMustChangePasswordAllowed(request.getRequestURI())) {
                    writeJson(response, HttpServletResponse.SC_FORBIDDEN, "首次登录必须修改密码");
                    return;
                }
            } else {
                // 认证失败：不设置 SecurityContext，后续由 401 处理器统一响应（避免信息泄露）
                log.debug("JWT 无效，请求路径: {}", request.getRequestURI());
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isMustChangePasswordAllowed(String uri) {
        if (uri == null) {
            return false;
        }
        for (String allowed : MUST_CHANGE_PASSWORD_ALLOWED) {
            if (uri.startsWith(allowed)) {
                return true;
            }
        }
        return false;
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"code\":" + status + ",\"message\":\"" + message + "\"}");
    }

    private static String extractBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }
}

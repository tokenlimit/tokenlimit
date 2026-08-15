package com.tokenlimit.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * SPA 路由 Fallback（前端 history 路由直达刷新场景）.
 * <p>前端为 Vue Router history 模式：直接访问 {@code /dashboard}、{@code /user} 等路径时
 * 服务端无对应 Controller，需统一回落到 {@code /index.html} 交由前端路由接管。</p>
 * <p>放行规则：</p>
 * <ul>
 *   <li>带扩展名的静态资源（{@code .js/.css/.png/...}）→ 正常处理</li>
 *   <li>{@code /api/**}、{@code /v1/**}（REST / OpenAI Compatible 网关）→ 正常处理</li>
 *   <li>{@code /error} 等特殊路径 → 正常处理</li>
 *   <li>其余无扩展名路径 → forward 到 {@code /index.html}</li>
 * </ul>
 * <p>本过滤器位于容器过滤器链（Spring Security 之前），仅做路径转发，不涉及鉴权；
 * {@code /index.html} 与静态资源已由 {@link SecurityConfig} 放行。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SpaFallbackFilter extends OncePerRequestFilter {

    /** 后端 API 前缀（含 OpenAI Compatible 网关），一律不参与 SPA Fallback */
    private static final String[] API_PREFIXES = {"/api/", "/v1/", "/error", "/actuator"};

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri == null || isApi(uri) || uri.contains(".")) {
            chain.doFilter(request, response);
            return;
        }
        // 无扩展名且非 API 的路径：SPA 路由，回落到 index.html
        request.getRequestDispatcher("/index.html").forward(request, response);
    }

    private boolean isApi(String uri) {
        for (String prefix : API_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

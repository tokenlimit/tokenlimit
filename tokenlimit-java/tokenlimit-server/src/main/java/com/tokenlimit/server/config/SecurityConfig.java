package com.tokenlimit.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.server.security.TokenAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * 统一安全配置（企业级）.
 * <p>无状态 API：不启用 Servlet Session，不启用 CSRF（前后端分离 + Bearer token），
 * 由 {@link TokenAuthenticationFilter} 解析 Bearer 会话 token 并注入 SecurityContext，
 * 通过 {@code @PreAuthorize} 做方法级授权。未认证统一返回 401、越权统一返回 403，
 * 响应体与业务 {@link Result} 结构一致。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final TokenAuthenticationFilter tokenAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(TokenAuthenticationFilter tokenAuthenticationFilter, ObjectMapper objectMapper) {
        this.tokenAuthenticationFilter = tokenAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 无状态：API 场景不需要 Session 与 CSRF
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // 公开端点：健康检查 / 登录 / 客户端数据面接口(API Key 自校验) / OpenAI Compatible Proxy 网关 / 错误转发
                        .requestMatchers("/api/v1/health", "/api/v1/admin/auth/login",
                                "/api/v1/client/**", "/v1/**", "/error").permitAll()
                        // 其余全部要求认证（未认证 → 401）
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        // 未认证 → 401
                        .authenticationEntryPoint((request, response, ex) ->
                                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        Result.failure(ErrorCode.UNAUTHORIZED.getCode(), "未登录或会话已过期")))
                        // 已认证但越权 → 403
                        .accessDeniedHandler((request, response, ex) ->
                                writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                                        Result.failure(ErrorCode.UNAUTHORIZED.getCode(), "无权访问该资源"))))
                .addFilterBefore(tokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeJson(HttpServletResponse response, int status, Result<?> body) {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            response.getWriter().write(objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            // 序列化失败时降级为纯文本
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        }
    }

    /**
     * 阻止容器自动注册认证过滤器：该过滤器仅挂在 Spring Security 链中执行，
     * 避免被 Servlet 容器重复执行导致双重认证。
     */
    @Bean
    public FilterRegistrationBean<TokenAuthenticationFilter> tokenAuthenticationFilterRegistration(
            TokenAuthenticationFilter filter) {
        FilterRegistrationBean<TokenAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 禁用 Spring Boot 自动生成的随机默认用户（登录完全走自研会话体系，不使用框架的 UserDetailsService）.
     */
    @Bean
    public org.springframework.security.core.userdetails.UserDetailsService userDetailsService() {
        return username -> {
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException(username);
        };
    }
}

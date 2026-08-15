package com.tokenlimit.server.security;

import com.tokenlimit.server.config.TokenLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 登录防爆破（Redis 计数，多实例共享）.
 * <p>与 JWT 无状态会话解耦的独立安全组件：仅记录/校验登录失败次数，
 * 不保存任何会话数据。key 结构：{@code {redis-prefix}:login-fail:{username}}，
 * 带 TTL 自动复位。</p>
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private static final String LOGIN_FAIL_PREFIX = "login-fail:";

    private final StringRedisTemplate redis;
    private final TokenLimitProperties properties;

    public LoginAttemptService(StringRedisTemplate redisTemplate, TokenLimitProperties properties) {
        this.redis = redisTemplate;
        this.properties = properties;
    }

    /**
     * 是否已触发登录锁定.
     */
    public boolean isLocked(String username) {
        if (!StringUtils.hasText(username)) {
            return false;
        }
        try {
            String value = redis.opsForValue().get(loginFailKey(username));
            if (value == null) {
                return false;
            }
            return Integer.parseInt(value) >= properties.getLoginMaxFails();
        } catch (Exception e) {
            // Redis 故障时放开限制，避免锁死所有登录
            log.warn("登录锁定状态读取失败，临时放行 username={}", username);
            return false;
        }
    }

    /**
     * 记录一次登录失败（带 TTL 自动复位，Redis 计数器多实例共享）.
     */
    public void recordFailure(String username) {
        if (!StringUtils.hasText(username)) {
            return;
        }
        try {
            String key = loginFailKey(username);
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofSeconds(properties.getLoginLockSeconds()));
            }
        } catch (Exception e) {
            log.warn("登录失败计数写入失败 username={}", username);
        }
    }

    /**
     * 登录成功后清除失败计数.
     */
    public void reset(String username) {
        if (!StringUtils.hasText(username)) {
            return;
        }
        try {
            redis.delete(loginFailKey(username));
        } catch (Exception e) {
            log.warn("登录失败计数清除失败 username={}", username);
        }
    }

    /**
     * 登录锁定窗口时长（秒），用于提示.
     */
    public long getLockSeconds() {
        return properties.getLoginLockSeconds();
    }

    private String loginFailKey(String username) {
        return properties.getRedisPrefix() + ":" + LOGIN_FAIL_PREFIX + username;
    }
}

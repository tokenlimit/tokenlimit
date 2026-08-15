package com.tokenlimit.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.server.config.TokenLimitProperties;
import com.tokenlimit.server.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;

/**
 * 管理端会话管理（基于 Redis，支持水平扩展）.
 * <p>会话数据存储在 Redis 而非进程内存，具备：</p>
 * <ul>
 *   <li>多实例共享：任意实例可校验任意实例签发的 token</li>
 *   <li>滑动过期：每次访问自动续期，闲置超时自动失效</li>
 *   <li>服务端可撤销：登出 / 改密 / 管理端可实时踢人</li>
 *   <li>256bit CSPRNG token，防猜测</li>
 *   <li>并发会话数限制（默认单会话，新登录顶掉旧会话）</li>
 * </ul>
 * <p>Redis key 结构（前缀可配置）：</p>
 * <ul>
 *   <li>{@code {prefix}:session:{token}} — 会话内容（JSON），带 TTL</li>
 *   <li>{@code {prefix}:user-sessions:{username}} — 用户活跃会话索引（ZSet，score=登录时间）</li>
 *   <li>{@code {prefix}:login-fail:{username}} — 登录失败计数（带 TTL 自动复位）</li>
 * </ul>
 */
@Service
public class AuthSession {

    private static final Logger log = LoggerFactory.getLogger(AuthSession.class);

    private static final String SESSION_PREFIX = "session:";
    private static final String USER_SESSIONS_PREFIX = "user-sessions:";
    private static final String LOGIN_FAIL_PREFIX = "login-fail:";

    /** token 随机字节数（256 bit） */
    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final TokenLimitProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthSession(StringRedisTemplate redisTemplate,
                       ObjectMapper objectMapper,
                       TokenLimitProperties properties) {
        this.redis = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 创建会话并返回 token.
     */
    public String create(SessionInfo session) {
        if (session == null || !StringUtils.hasText(session.getUsername())) {
            throw new IllegalArgumentException("会话信息不完整");
        }
        String token = generateToken();
        redis.opsForValue().set(
                sessionKey(token), toJson(session), Duration.ofSeconds(properties.getSessionTtlSeconds()));

        // 维护用户会话索引（ZSet，score=登录时间，用于按序淘汰）
        String indexKey = userSessionsKey(session.getUsername());
        long score = session.getLoginAt() == null
                ? System.currentTimeMillis()
                : session.getLoginAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        redis.opsForZSet().add(indexKey, token, score);

        // 会话数上限控制：超过上限则淘汰最旧会话
        int max = properties.getMaxSessionsPerUser();
        if (max > 0) {
            Long size = redis.opsForZSet().size(indexKey);
            if (size != null && size > max) {
                Set<String> oldest = redis.opsForZSet().range(indexKey, 0, size - max - 1);
                if (oldest != null) {
                    for (String oldToken : oldest) {
                        removeSession(oldToken, session.getUsername());
                        log.info("并发会话数超过上限({})，淘汰旧会话", max);
                    }
                }
            }
        }
        return token;
    }

    /**
     * 根据 token 获取会话（命中后滑动续期），不存在返回 null.
     */
    public SessionInfo get(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String key = sessionKey(token);
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            SessionInfo session = objectMapper.readValue(json, SessionInfo.class);
            // 滑动续期：每次有效访问重置过期时间
            redis.expire(key, Duration.ofSeconds(properties.getSessionTtlSeconds()));
            return session;
        } catch (JsonProcessingException e) {
            log.warn("会话反序列化失败，token={}，删除该会话", mask(token), e);
            redis.delete(key);
            return null;
        }
    }

    /**
     * 获取会话所属用户名，无效返回 null.
     */
    public String usernameOf(String token) {
        SessionInfo session = get(token);
        return session == null ? null : session.getUsername();
    }

    /**
     * 要求合法会话并返回用户名，否则抛 401.
     */
    public String requireUsername(String token) {
        SessionInfo session = get(token);
        if (session == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return session.getUsername();
    }

    /**
     * 注销指定会话（幂等）.
     */
    public void destroy(String token) {
        if (!StringUtils.hasText(token)) {
            return;
        }
        String key = sessionKey(token);
        String json = redis.opsForValue().get(key);
        if (json != null) {
            try {
                SessionInfo session = objectMapper.readValue(json, SessionInfo.class);
                removeSession(token, session.getUsername());
                return;
            } catch (JsonProcessingException e) {
                log.warn("会话反序列化失败，token={}，直接删除", mask(token), e);
            }
        }
        redis.delete(key);
    }

    /**
     * 注销某用户全部会话（如账号被禁用、全端下线）.
     */
    public void destroyAll(String username) {
        if (!StringUtils.hasText(username)) {
            return;
        }
        String indexKey = userSessionsKey(username);
        Set<String> tokens = redis.opsForZSet().range(indexKey, 0, -1);
        redis.delete(indexKey);
        if (tokens != null) {
            for (String token : tokens) {
                redis.delete(sessionKey(token));
            }
        }
        log.info("已注销用户 {} 的全部会话", username);
    }

    /**
     * 注销指定会话之外的同用户其它会话（如改密后踢掉其它设备，保留当前登录）.
     */
    public void destroyOthers(String token) {
        SessionInfo session = get(token);
        if (session == null) {
            return;
        }
        String indexKey = userSessionsKey(session.getUsername());
        Set<String> tokens = redis.opsForZSet().range(indexKey, 0, -1);
        if (tokens != null) {
            for (String other : tokens) {
                if (!other.equals(token)) {
                    removeSession(other, session.getUsername());
                }
            }
        }
    }

    /**
     * 修改密码成功后清除当前会话的强制改密标记（保留登录态，不踢当前设备）.
     */
    public void markPasswordChanged(String token) {
        if (!StringUtils.hasText(token)) {
            return;
        }
        String key = sessionKey(token);
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return;
        }
        try {
            SessionInfo session = objectMapper.readValue(json, SessionInfo.class);
            session.setMustChangePassword(false);
            redis.opsForValue().set(key, toJson(session),
                    Duration.ofSeconds(properties.getSessionTtlSeconds()));
        } catch (JsonProcessingException e) {
            log.warn("会话反序列化失败，token={}，无法清除改密标记", mask(token), e);
        }
    }

    /**
     * 是否已触发登录锁定.
     */
    public boolean isLoginLocked(String username) {
        if (!StringUtils.hasText(username)) {
            return false;
        }
        String value = redis.opsForValue().get(loginFailKey(username));
        if (value == null) {
            return false;
        }
        try {
            return Integer.parseInt(value) >= properties.getLoginMaxFails();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 记录一次登录失败（带 TTL 自动复位，Redis 计数器多实例共享）.
     */
    public void recordLoginFailure(String username) {
        if (!StringUtils.hasText(username)) {
            return;
        }
        String key = loginFailKey(username);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofSeconds(properties.getLoginLockSeconds()));
        }
    }

    /**
     * 登录成功后清除失败计数.
     */
    public void resetLoginFailures(String username) {
        if (!StringUtils.hasText(username)) {
            return;
        }
        redis.delete(loginFailKey(username));
    }

    /**
     * 登录锁定窗口时长（秒），用于提示.
     */
    public long getLoginLockSeconds() {
        return properties.getLoginLockSeconds();
    }

    private void removeSession(String token, String username) {
        redis.delete(sessionKey(token));
        if (StringUtils.hasText(username)) {
            redis.opsForZSet().remove(userSessionsKey(username), token);
        }
    }

    /**
     * 生成 256bit CSPRNG token（Base64 URL 安全编码，无 padding）.
     */
    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String toJson(SessionInfo session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("会话序列化失败", e);
        }
    }

    private String sessionKey(String token) {
        return properties.getRedisPrefix() + ":" + SESSION_PREFIX + token;
    }

    private String userSessionsKey(String username) {
        return properties.getRedisPrefix() + ":" + USER_SESSIONS_PREFIX + username;
    }

    private String loginFailKey(String username) {
        return properties.getRedisPrefix() + ":" + LOGIN_FAIL_PREFIX + username;
    }

    /** 脱敏 token，避免日志泄露完整会话凭据 */
    private String mask(String token) {
        if (token == null || token.length() <= 8) {
            return "****";
        }
        return token.substring(0, 6) + "****" + token.substring(token.length() - 4);
    }

    /**
     * 会话身份快照（登录时固化，避免后续每次请求查库）.
     */
    public static class SessionInfo {

        private String username;
        private String userName;
        private String role;
        private String teamCode;
        private String userCode;
        private boolean mustChangePassword;
        private LocalDateTime loginAt;

        public SessionInfo() {
        }

        public SessionInfo(User user, boolean mustChangePassword) {
            this.username = user.getUsername();
            this.userName = user.getUserName();
            this.role = user.getRole();
            this.teamCode = user.getTeamCode();
            this.userCode = user.getUserCode();
            this.mustChangePassword = mustChangePassword;
            this.loginAt = LocalDateTime.now();
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getTeamCode() {
            return teamCode;
        }

        public void setTeamCode(String teamCode) {
            this.teamCode = teamCode;
        }

        public String getUserCode() {
            return userCode;
        }

        public void setUserCode(String userCode) {
            this.userCode = userCode;
        }

        public boolean isMustChangePassword() {
            return mustChangePassword;
        }

        public void setMustChangePassword(boolean mustChangePassword) {
            this.mustChangePassword = mustChangePassword;
        }

        public LocalDateTime getLoginAt() {
            return loginAt;
        }

        public void setLoginAt(LocalDateTime loginAt) {
            this.loginAt = loginAt;
        }
    }
}

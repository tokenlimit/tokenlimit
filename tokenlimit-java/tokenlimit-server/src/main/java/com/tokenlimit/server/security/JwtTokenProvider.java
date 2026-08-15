package com.tokenlimit.server.security;

import com.tokenlimit.server.config.TokenLimitProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;

/**
 * JWT 令牌签发与解析（无状态，不依赖 Redis 会话）.
 * <p>Admin 端登录后签发 HS256 JWT，将登录身份快照（{@link SessionInfo}）固化进 claims；
 * 后续请求仅凭 JWT 校验，无任何服务端会话存储，天然支持多实例水平扩展。</p>
 * <p>安全说明：</p>
 * <ul>
 *   <li>secret 必须 ≥ 32 字节（HS256），支持 Base64 或纯文本配置</li>
 *   <li>JWT 为无状态令牌，服务端无法即时吊销；登出由前端丢弃令牌，
 *       改密/禁用账号后旧令牌最长存活至过期（可通过缩短 {@code expire-seconds} 收敛风险）</li>
 *   <li>登录防爆破由 {@link LoginAttemptService} 独立承担，与本组件解耦</li>
 * </ul>
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final SecretKey key;
    private final String issuer;
    private final long expireSeconds;

    public JwtTokenProvider(TokenLimitProperties properties) {
        byte[] secretBytes = decodeSecret(properties.getJwt().getSecret());
        if (secretBytes.length < 32) {
            // HS256 要求密钥 ≥ 256 bit；不足时以 0x00 填充，避免配置错误导致启动失败
            secretBytes = Arrays.copyOf(secretBytes, 32);
            log.warn("JWT secret 长度不足 32 字节，已自动补齐；生产环境请配置足够强度的 secret");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.issuer = properties.getJwt().getIssuer();
        this.expireSeconds = properties.getJwt().getExpireSeconds();
    }

    /**
     * 生成 JWT（HS256）.
     */
    public String generate(SessionInfo session) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expireSeconds * 1000);
        return Jwts.builder()
                .subject(session.getUsername())
                .claim("userName", session.getUserName())
                .claim("role", session.getRole())
                .claim("teamCode", session.getTeamCode())
                .claim("userCode", session.getUserCode())
                .claim("mustChangePassword", session.isMustChangePassword())
                .claim("loginAt", session.getLoginAt() == null
                        ? now.getTime()
                        : session.getLoginAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .issuer(issuer)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验 JWT；签名/过期/签发者任一不合法均返回 {@code null}.
     */
    public SessionInfo parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            SessionInfo session = new SessionInfo();
            session.setUsername(claims.getSubject());
            session.setUserName(claims.get("userName", String.class));
            session.setRole(claims.get("role", String.class));
            session.setTeamCode(claims.get("teamCode", String.class));
            session.setUserCode(claims.get("userCode", String.class));
            session.setMustChangePassword(Boolean.TRUE.equals(claims.get("mustChangePassword", Boolean.class)));
            Long loginAt = claims.get("loginAt", Long.class);
            if (loginAt != null) {
                session.setLoginAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(loginAt), ZoneId.systemDefault()));
            }
            return session;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 校验失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 令牌有效期（秒），供提示与测试使用.
     */
    public long getExpireSeconds() {
        return expireSeconds;
    }

    private byte[] decodeSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            return new byte[0];
        }
        // 优先按 Base64 解码，失败则按 UTF-8 纯文本处理
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }
}

package com.tokenlimit.server.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 用户密码哈希工具（BCrypt）.
 * <p>存储格式：{@code {bcrypt}$2a$10$...}。
 * 兼容旧版裸 SHA-256（无盐）哈希：首次登录校验通过后由 AuthAdminController 自动升级为 BCrypt。</p>
 */
public final class PasswordHash {

    /** BCrypt 存储前缀 */
    public static final String BCRYPT_PREFIX = "{bcrypt}";

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(10);

    /** 旧版 SHA-256 哈希的十六进制长度 */
    private static final int LEGACY_SHA256_HEX_LEN = 64;
    private static final String LEGACY_HEX_PATTERN = "[0-9a-fA-F]{64}";

    private PasswordHash() {
    }

    /**
     * 计算 BCrypt 密码哈希（自动加盐）.
     */
    public static String hash(String rawPassword) {
        return BCRYPT_PREFIX + ENCODER.encode(rawPassword);
    }

    /**
     * 校验明文密码是否匹配存储的哈希（兼容 BCrypt 与旧版 SHA-256）.
     */
    public static boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null) {
            return false;
        }
        if (storedHash.startsWith(BCRYPT_PREFIX)) {
            return ENCODER.matches(rawPassword, storedHash.substring(BCRYPT_PREFIX.length()));
        }
        // 旧版裸 SHA-256，兼容校验（仅用于迁移期；注意：API Key 哈希已改为 HMAC-SHA256 + pepper）
        if (storedHash.matches(LEGACY_HEX_PATTERN)) {
            return verifyLegacySha256(rawPassword, storedHash);
        }
        return false;
    }

    /**
     * 旧版裸 SHA-256 校验（用户密码迁移期兼容，无盐、不参与 pepper）.
     */
    private static boolean verifyLegacySha256(String rawPassword, String storedHash) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawPassword.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return MessageDigest.isEqual(
                    sb.toString().getBytes(StandardCharsets.UTF_8),
                    storedHash.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希校验失败", e);
        }
    }

    /**
     * 是否为旧版哈希（需要升级为 BCrypt）.
     */
    public static boolean isLegacy(String storedHash) {
        return storedHash != null && !storedHash.startsWith(BCRYPT_PREFIX);
    }
}

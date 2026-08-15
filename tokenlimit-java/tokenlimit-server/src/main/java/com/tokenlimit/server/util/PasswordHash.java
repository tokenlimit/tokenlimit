package com.tokenlimit.server.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

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
        // 旧版裸 SHA-256，兼容校验（仅用于迁移期）
        if (storedHash.matches(LEGACY_HEX_PATTERN)) {
            return SecretUtils.verifySecret(rawPassword, storedHash);
        }
        return false;
    }

    /**
     * 是否为旧版哈希（需要升级为 BCrypt）.
     */
    public static boolean isLegacy(String storedHash) {
        return storedHash != null && !storedHash.startsWith(BCRYPT_PREFIX);
    }
}

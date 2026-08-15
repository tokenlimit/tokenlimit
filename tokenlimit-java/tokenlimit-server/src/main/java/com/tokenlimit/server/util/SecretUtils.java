package com.tokenlimit.server.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * API Key secret 哈希工具.
 * <p>MVP 阶段使用简单 SHA-256 哈希；生产可替换为 bcrypt。</p>
 */
public final class SecretUtils {

    private SecretUtils() {
    }

    /**
     * 计算 secret 的 SHA-256 哈希（十六进制小写）.
     */
    public static String hashSecret(String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(secret.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("密钥哈希失败", e);
        }
    }

    /**
     * 常量时间比较两个哈希值，避免时序攻击.
     */
    public static boolean verifySecret(String secret, String secretHash) {
        if (secret == null || secretHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hashSecret(secret).getBytes(StandardCharsets.UTF_8),
                secretHash.getBytes(StandardCharsets.UTF_8));
    }
}

package com.tokenlimit.server.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * API Key secret 哈希工具（HMAC-SHA256 + 服务端密钥 pepper）.
 * <p>哈希 = HMAC-SHA256(pepper, secret)。pepper 由服务端独占（配置项
 * {@code tokenlimit.hash-pepper}），即使数据库整体泄露，攻击者缺少 pepper
 * 也无法离线碰撞 secret；secret 本身为 192-bit 随机串，二者结合满足生产安全要求。</p>
 */
public final class SecretUtils {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private SecretUtils() {
    }

    /**
     * 计算 secret 的 HMAC-SHA256 哈希（十六进制小写）.
     */
    public static String hashSecret(String secret, String pepper) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(secret.getBytes(StandardCharsets.UTF_8));
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
    public static boolean verifySecret(String secret, String secretHash, String pepper) {
        if (secret == null || secretHash == null || pepper == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hashSecret(secret, pepper).getBytes(StandardCharsets.UTF_8),
                secretHash.getBytes(StandardCharsets.UTF_8));
    }
}

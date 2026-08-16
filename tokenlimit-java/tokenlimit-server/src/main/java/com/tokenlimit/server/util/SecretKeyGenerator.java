package com.tokenlimit.server.util;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 生产密钥生成器：一键生成 application.yml 所需的随机密钥.
 * <p>直接运行 {@link #main} 即可，无需启动 Spring 上下文。</p>
 * <ul>
 *   <li>{@code tokenlimit.jwt.secret}：HS256 签名密钥，Base64 编码（解码后 32 字节 = 256 bit）</li>
 *   <li>{@code tokenlimit.hash-pepper}：API Key 哈希服务端密钥，Base64 编码（UTF-8 字节 44 字节 ≥ 32 字节）</li>
 * </ul>
 */
public final class SecretKeyGenerator {

    /** 密钥长度（字节）：HS256 / HMAC-SHA256 均要求 ≥ 32 字节（256 bit） */
    private static final int KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretKeyGenerator() {
    }

    public static void main(String[] args) {
        String jwtSecret = generateBase64Key();
        String hashPepper = generateBase64Key();

        System.out.println("==================================================");
        System.out.println(" TokenLimit 生产密钥生成结果（每次运行均随机）");
        System.out.println("==================================================");
        System.out.println();
        System.out.println("1. JWT 签名密钥 -> tokenlimit.jwt.secret");
        System.out.println("   要求：>= 32 字节（HS256）；Base64 编码，解码后 " + KEY_BYTES + " 字节");
        System.out.println("   " + jwtSecret);
        System.out.println();
        System.out.println("2. API Key 哈希 pepper -> tokenlimit.hash-pepper");
        System.out.println("   要求：>= 32 字节（HMAC-SHA256）；Base64 串按 UTF-8 使用，共 44 字节");
        System.out.println("   " + hashPepper);
        System.out.println();
        System.out.println("3. 示例 API Key（本地联调用，可复制到 Cursor / cURL）：");
        System.out.println("   " + generateApiKey());
        System.out.println();
        System.out.println("--------------------------------------------------");
        System.out.println(" 写入生产 application.yml（替换默认开发值）：");
        System.out.println("   tokenlimit:");
        System.out.println("     jwt:");
        System.out.println("       secret: " + jwtSecret);
        System.out.println("     hash-pepper: " + hashPepper);
        System.out.println("--------------------------------------------------");
        System.out.println(" 安全提示：");
        System.out.println(" - 密钥请通过环境变量 / 私密配置注入，勿提交到 Git");
        System.out.println(" - pepper 一旦泄露需轮换，请妥善保管");
    }

    /**
     * 生成 Base64 编码的随机密钥（KEY_BYTES 字节随机数 → Base64）.
     */
    public static String generateBase64Key() {
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 生成示例 API Key：accessKey 与 secret 冒号拼接（对齐大厂凭证格式）.
     * <p>accessKey = {@code tl_ak_} + 32 位 base62（≈190 bit 熵，同 ApiKeyAdminController 生成策略）；
     * secret = {@code sk_tl_} + 48 位 hex（192 bit）。</p>
     */
    public static String generateApiKey() {
        return "tl_ak_" + randomBase62(ACCESS_KEY_RANDOM_LEN) + ":" + "sk_tl_" + randomHex(SECRET_HEX_LEN);
    }

    /** accessKey 随机段长度：32 位 base62 ≈ 190 bit 熵 */
    private static final int ACCESS_KEY_RANDOM_LEN = 32;

    /** secret 随机段长度：48 位 hex = 192 bit 熵 */
    private static final int SECRET_HEX_LEN = 48;

    private static final char[] BASE62_CHARS =
            ("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz").toCharArray();
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    /**
     * 生成指定长度的 base62 随机串（SecureRandom，nextInt 无模偏差）.
     */
    private static String randomBase62(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62_CHARS[RANDOM.nextInt(BASE62_CHARS.length)]);
        }
        return sb.toString();
    }

    /**
     * 生成指定长度的 hex 随机串.
     */
    private static String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(HEX_CHARS[RANDOM.nextInt(HEX_CHARS.length)]);
        }
        return sb.toString();
    }
}

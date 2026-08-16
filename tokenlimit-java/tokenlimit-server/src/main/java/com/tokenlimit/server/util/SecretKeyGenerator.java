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
}

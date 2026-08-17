package com.tokenlimit.server.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 供应商密钥 AES-GCM 加密工具（PRD V4.0）.
 * <p>使用配置中的 cryptoKey 派生密钥；密文格式：base64(iv + ciphertext)，iv 12 字节。</p>
 */
public final class CredentialCryptoUtils {

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;
    private static final String PASSWORD = System.getenv().getOrDefault(
            "TOKENLIMIT_CRYPTO_KEY", "tokenlimit-change-me-please-2026");

    private CredentialCryptoUtils() {
    }

    private static SecretKey deriveKey() throws Exception {
        PBEKeySpec spec = new PBEKeySpec(PASSWORD.toCharArray(), "tokenlimit-salt".getBytes(StandardCharsets.UTF_8), 10000, 256);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    public static String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return "enc:" + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("凭证加密失败", e);
        }
    }

    public static String decrypt(String enc) {
        if (enc == null || enc.isBlank()) {
            return null;
        }
        if (!enc.startsWith("enc:")) {
            // 兼容未加密的旧数据
            return enc;
        }
        try {
            byte[] all = Base64.getDecoder().decode(enc.substring(4));
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("凭证解密失败", e);
        }
    }
}

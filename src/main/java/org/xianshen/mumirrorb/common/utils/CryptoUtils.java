package org.xianshen.mumirrorb.common.utils;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM 加密工具类
 *
 * <p>用于加密/解密敏感数据（如 API Key）。</p>
 * <p>使用 AES-256-GCM 模式，每次加密生成随机 IV，安全性较高。</p>
 *
 * <p><strong>加密格式：</strong></p>
 * <pre>
 * Base64(IV[12] + Ciphertext + Tag[16])
 * </pre>
 */
@Slf4j
public class CryptoUtils {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    // 默认密钥（32 字节 = 256 位），生产环境应从配置读取
    // TODO: 从 application.yml 或环境变量读取
    private static final String DEFAULT_KEY = "MuMirror-DefaultKey-2026!@#$%^&*";

    /**
     * 获取加密密钥
     */
    private static SecretKeySpec getKey() {
        byte[] keyBytes = DEFAULT_KEY.getBytes(StandardCharsets.UTF_8);
        // 确保密钥长度为 32 字节
        byte[] paddedKey = new byte[32];
        System.arraycopy(keyBytes, 0, paddedKey, 0, Math.min(keyBytes.length, 32));
        return new SecretKeySpec(paddedKey, ALGORITHM);
    }

    /**
     * 加密字符串
     *
     * @param plainText 明文
     * @return Base64 编码的密文（含 IV），null 如果明文为 null
     */
    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }

        try {
            // 生成随机 IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            // 初始化加密器
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, getKey(), parameterSpec);

            // 加密
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 拼接 IV + 密文
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            log.error("加密失败", e);
            throw new RuntimeException("加密失败", e);
        }
    }

    /**
     * 解密字符串
     *
     * @param cipherText Base64 编码的密文（含 IV）
     * @return 明文，null 如果密文为 null
     */
    public static String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return cipherText;
        }

        try {
            // 解码 Base64
            byte[] decoded = Base64.getDecoder().decode(cipherText);
            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);

            // 提取 IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);

            // 提取密文
            byte[] cipherBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherBytes);

            // 初始化解密器
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, getKey(), parameterSpec);

            // 解密
            byte[] plainBytes = cipher.doFinal(cipherBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("解密失败", e);
            throw new RuntimeException("解密失败", e);
        }
    }

    /**
     * 脱敏处理（用于返回给前端）
     *
     * <p>只显示前 3 位 + ***，如果长度不足 3 位则全部显示 ***</p>
     *
     * @param value 原始值
     * @return 脱敏后的值
     */
    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 3) {
            return "***";
        }
        return value.substring(0, 3) + "***";
    }
}

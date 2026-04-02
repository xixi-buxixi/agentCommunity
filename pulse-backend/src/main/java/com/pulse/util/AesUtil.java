package com.pulse.util;

import cn.hutool.crypto.symmetric.AES;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * AES Encryption Utility
 *
 * Used for encrypting/decrypting API Key storage.
 * API Keys must NEVER be stored in plaintext.
 */
@Slf4j
@Component
public class AesUtil {

    @Value("${aes.secret-key}")
    private String secretKey;

    /**
     * Get AES instance with configured key
     */
    private AES getAes() {
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        // Ensure key is 16 bytes (128-bit AES)
        byte[] adjustedKey = new byte[16];
        System.arraycopy(keyBytes, 0, adjustedKey, 0, Math.min(keyBytes.length, 16));
        return new AES(adjustedKey);
    }

    /**
     * Encrypt plaintext string
     *
     * @param plaintext Plain text to encrypt
     * @return Base64 encoded encrypted string
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            AES aes = getAes();
            return aes.encryptBase64(plaintext);
        } catch (Exception e) {
            log.error("AES encryption failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Decrypt encrypted string
     *
     * @param encrypted Base64 encoded encrypted string
     * @return Decrypted plaintext
     */
    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return null;
        }
        try {
            AES aes = getAes();
            return aes.decryptStr(encrypted);
        } catch (Exception e) {
            log.error("AES decryption failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Mask API Key for display (show last 4 chars only)
     *
     * @param apiKey Original API Key (decrypted)
     * @return Masked API Key like "sk-****12ab"
     */
    public String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "****";
        }
        int visibleLength = 4;
        String visiblePart = apiKey.substring(apiKey.length() - visibleLength);
        String prefixPart = apiKey.substring(0, Math.min(4, apiKey.length() - visibleLength));
        return prefixPart + "****" + visiblePart;
    }
}
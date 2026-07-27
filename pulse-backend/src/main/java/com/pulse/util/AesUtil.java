package com.pulse.util;

import cn.hutool.crypto.symmetric.AES;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 * AES Encryption Utility
 *
 * Used for encrypting/decrypting API Key storage.
 * API Keys must NEVER be stored in plaintext.
 *
 * Format: {@code v2:<base64 iv>:<base64 ciphertext+tag>} using AES-256/GCM.
 *
 * Why GCM and not the previous Hutool default (AES/ECB/PKCS5Padding):
 * - ECB is deterministic, so identical API keys produced identical ciphertext,
 *   revealing which agents share a key, and blocks could be reordered or swapped.
 * - ECB has no integrity tag: tampered ciphertext decrypted to garbage unnoticed.
 * - The key was zero-padded/truncated to 16 bytes, so "AES256" was really AES-128
 *   with reduced entropy.
 *
 * Ciphertext written before this change has no "v2:" prefix and is decrypted with
 * the legacy scheme, using AES_SECRET_LEGACY when the key was rotated at the same
 * time. Rows are upgraded lazily: whenever an agent's API key is written again it
 * is stored in the new format.
 */
@Slf4j
@Component
public class AesUtil {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String NEW_FORMAT_PREFIX = "v2:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 120_000;

    /**
     * Fixed salt: the input secret is expected to be high-entropy (openssl rand),
     * so the derivation only needs domain separation, not per-record salting.
     */
    private static final byte[] KDF_SALT = "pulse-apikey-aes-gcm-v2".getBytes(StandardCharsets.UTF_8);

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${aes.secret-key}")
    private String secretKey;

    /**
     * Key that pre-GCM ciphertext was encrypted with. Empty when the key was never
     * rotated, in which case the current secret is used for legacy reads too.
     */
    @Value("${aes.legacy-secret-key:}")
    private String legacySecretKey;

    private SecretKey derivedKey;

    @PostConstruct
    void init() {
        this.derivedKey = deriveKey(secretKey);
    }

    /**
     * Encrypt plaintext string
     *
     * @param plaintext Plain text to encrypt
     * @return {@code v2:<base64 iv>:<base64 ciphertext>}
     * @throws BusinessException when encryption fails - never returns null on error,
     *                           because a silently dropped API key looks like a
     *                           successful save to the user
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, derivedKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            Base64.Encoder encoder = Base64.getEncoder();
            return NEW_FORMAT_PREFIX + encoder.encodeToString(iv) + ":" + encoder.encodeToString(ciphertext);
        } catch (Exception e) {
            log.error("AES encryption failed", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
    }

    /**
     * Decrypt encrypted string (both the current and the legacy format)
     *
     * @param encrypted stored ciphertext
     * @return Decrypted plaintext
     * @throws BusinessException when the ciphertext cannot be decrypted or its
     *                           integrity tag does not verify
     */
    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return null;
        }
        try {
            if (encrypted.startsWith(NEW_FORMAT_PREFIX)) {
                return decryptGcm(encrypted);
            }
            return decryptLegacyEcb(encrypted);
        } catch (Exception e) {
            log.error("AES decryption failed (format={})",
                    encrypted.startsWith(NEW_FORMAT_PREFIX) ? "gcm" : "legacy-ecb", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
    }

    /**
     * True when the stored value still uses the pre-GCM format and should be
     * rewritten the next time it is saved.
     */
    public boolean isLegacyFormat(String encrypted) {
        return encrypted != null && !encrypted.isEmpty() && !encrypted.startsWith(NEW_FORMAT_PREFIX);
    }

    private String decryptGcm(String encrypted) throws Exception {
        String[] parts = encrypted.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("malformed ciphertext");
        }
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] iv = decoder.decode(parts[1]);
        byte[] ciphertext = decoder.decode(parts[2]);

        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, derivedKey, new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    /**
     * Legacy path: Hutool AES/ECB/PKCS5Padding with the key truncated/zero-padded
     * to 16 bytes. Read-only - nothing is ever written in this format again.
     */
    private String decryptLegacyEcb(String encrypted) {
        String keySource = (legacySecretKey != null && !legacySecretKey.isBlank())
                ? legacySecretKey
                : secretKey;
        byte[] keyBytes = keySource.getBytes(StandardCharsets.UTF_8);
        byte[] adjustedKey = new byte[16];
        System.arraycopy(keyBytes, 0, adjustedKey, 0, Math.min(keyBytes.length, 16));
        return new AES(adjustedKey).decryptStr(encrypted);
    }

    private SecretKey deriveKey(String source) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(source.toCharArray(), KDF_SALT, PBKDF2_ITERATIONS, KEY_BITS);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive AES key from aes.secret-key", e);
        }
    }

    /**
     * Mask API Key for display (show last 4 chars only)
     *
     * @param apiKey Original API Key (decrypted)
     * @return Masked API Key like "sk-****12ab"
     */
    public String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 12) {
            // Below 12 characters prefix+suffix would reveal the entire value
            // (an 8-character key used to be printed in full).
            return "****";
        }
        String visiblePart = apiKey.substring(apiKey.length() - 4);
        String prefixPart = apiKey.substring(0, 4);
        return prefixPart + "****" + visiblePart;
    }
}

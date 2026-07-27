package com.pulse.util;

import cn.hutool.crypto.symmetric.AES;
import com.pulse.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for H8: AES-GCM storage format with a read path for legacy ECB ciphertext.
 */
class AesUtilTest {

    private static final String CURRENT_KEY = "3f1c9b7e5a2d84c60f9e1b3a7d5c8e20";
    private static final String LEGACY_KEY = "PulseAES256SecretKey!";

    private AesUtil aesUtil;

    @BeforeEach
    void setUp() {
        aesUtil = new AesUtil();
        ReflectionTestUtils.setField(aesUtil, "secretKey", CURRENT_KEY);
        ReflectionTestUtils.setField(aesUtil, "legacySecretKey", LEGACY_KEY);
        ReflectionTestUtils.invokeMethod(aesUtil, "init");
    }

    @Test
    void roundTripsThroughTheNewFormat() {
        String plaintext = "sk-proj-abcdefghijklmnopqrstuvwxyz";

        String encrypted = aesUtil.encrypt(plaintext);

        assertThat(encrypted).startsWith("v2:");
        assertThat(encrypted).doesNotContain(plaintext);
        assertThat(aesUtil.decrypt(encrypted)).isEqualTo(plaintext);
    }

    /**
     * The core reason for moving off ECB: identical plaintext must not produce
     * identical ciphertext, otherwise it is visible which agents share a key.
     */
    @Test
    void sameInputProducesDifferentCiphertextEachTime() {
        String plaintext = "sk-same-input-every-time";

        String first = aesUtil.encrypt(plaintext);
        String second = aesUtil.encrypt(plaintext);

        assertThat(first).isNotEqualTo(second);
        assertThat(aesUtil.decrypt(first)).isEqualTo(plaintext);
        assertThat(aesUtil.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    void tamperedCiphertextIsRejectedInsteadOfReturningGarbage() {
        String encrypted = aesUtil.encrypt("sk-integrity-protected");
        String[] parts = encrypted.split(":", 3);
        // Flip a character of the ciphertext body
        char flipped = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        String tampered = parts[0] + ":" + parts[1] + ":" + flipped + parts[2].substring(1);

        assertThatThrownBy(() -> aesUtil.decrypt(tampered))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void readsCiphertextWrittenByTheLegacyEcbScheme() {
        // Reproduce exactly what the old implementation stored
        byte[] keyBytes = LEGACY_KEY.getBytes(StandardCharsets.UTF_8);
        byte[] adjusted = new byte[16];
        System.arraycopy(keyBytes, 0, adjusted, 0, Math.min(keyBytes.length, 16));
        String legacyCiphertext = new AES(adjusted).encryptBase64("sk-legacy-stored-key");

        assertThat(aesUtil.isLegacyFormat(legacyCiphertext)).isTrue();
        assertThat(aesUtil.decrypt(legacyCiphertext)).isEqualTo("sk-legacy-stored-key");
    }

    @Test
    void newFormatIsNotFlaggedAsLegacy() {
        assertThat(aesUtil.isLegacyFormat(aesUtil.encrypt("sk-new"))).isFalse();
    }

    @Test
    void encryptionFailureThrowsInsteadOfSilentlyReturningNull() {
        AesUtil broken = new AesUtil();
        ReflectionTestUtils.setField(broken, "secretKey", CURRENT_KEY);
        ReflectionTestUtils.invokeMethod(broken, "init");
        // Corrupt the derived key so the cipher cannot be initialised
        ReflectionTestUtils.setField(broken, "derivedKey", null);

        assertThatThrownBy(() -> broken.encrypt("sk-value"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void nullAndEmptyInputsStayNull() {
        assertThat(aesUtil.encrypt(null)).isNull();
        assertThat(aesUtil.encrypt("")).isNull();
        assertThat(aesUtil.decrypt(null)).isNull();
        assertThat(aesUtil.decrypt("")).isNull();
    }

    @Test
    void maskingNeverRevealsAShortKeyInFull() {
        // An 8-character key used to be printed completely (prefix 4 + visible 4)
        assertThat(aesUtil.maskApiKey("12345678")).isEqualTo("****");
        assertThat(aesUtil.maskApiKey("sk-1234567")).isEqualTo("****");
        assertThat(aesUtil.maskApiKey(null)).isEqualTo("****");

        String masked = aesUtil.maskApiKey("sk-abcdefghijklmnop");
        assertThat(masked).isEqualTo("sk-a****mnop");
        assertThat(masked).doesNotContain("efghij");
    }
}

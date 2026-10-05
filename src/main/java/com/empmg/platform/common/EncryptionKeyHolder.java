package com.empmg.platform.common;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Holds the AES-256 key used to encrypt salary/compensation fields at rest.
 * Fails fast at startup if the key is missing — silent data loss is worse than
 * a refused boot.
 */
@Component
@RequiredArgsConstructor
public class EncryptionKeyHolder {

    private final AppProperties props;

    private SecretKeySpec key;

    @PostConstruct
    void init() {
        String raw = props.getEncryption().getKey();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("""
                    DATA_ENCRYPTION_KEY is not set. Copy .env.example to .env and use the \
                    generated key, or set your own base64 32-byte key.""");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("DATA_ENCRYPTION_KEY must be valid base64 (32 bytes).");
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("DATA_ENCRYPTION_KEY must decode to exactly 32 bytes for AES-256.");
        }
        this.key = new SecretKeySpec(bytes, "AES");
        // Hibernate builds JPA converters itself; hand it the key once, statically.
        EncryptedMoneyConverter.setHolder(this);
    }

    public SecretKeySpec get() {
        return key;
    }
}

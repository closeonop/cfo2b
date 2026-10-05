package com.empmg.platform.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/**
 * Encrypts money fields (salaries, raise amounts, variable pay values) with
 * AES-256-GCM before they touch the database. Each value gets a fresh 12-byte
 * IV, stored in front of the ciphertext. Database backups and dumps therefore
 * never contain readable compensation data.
 */
@Converter
public class EncryptedMoneyConverter implements AttributeConverter<BigDecimal, byte[]> {

    // set once by EncryptionKeyHolder at startup; Hibernate instantiates converters itself
    private static EncryptionKeyHolder holder;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    public static void setHolder(EncryptionKeyHolder encryptionKeyHolder) {
        holder = encryptionKeyHolder;
    }

    @Override
    public byte[] convertToDatabaseColumn(BigDecimal attribute) {
        if (attribute == null) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, holder.get(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = attribute.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            byte[] enc = cipher.doFinal(plain);
            return ByteBuffer.allocate(IV_LEN + enc.length).put(iv).put(enc).array();
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt salary data", e);
        }
    }

    @Override
    public BigDecimal convertToEntityAttribute(byte[] dbData) {
        if (dbData == null) return null;
        try {
            ByteBuffer buf = ByteBuffer.wrap(dbData);
            byte[] iv = new byte[IV_LEN];
            buf.get(iv);
            byte[] enc = new byte[buf.remaining()];
            buf.get(enc);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, holder.get(), new GCMParameterSpec(TAG_BITS, iv));
            return new BigDecimal(new String(cipher.doFinal(enc), java.nio.charset.StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException("Could not decrypt salary data — wrong DATA_ENCRYPTION_KEY?", e);
        }
    }
}

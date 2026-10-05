package com.empmg.platform.common;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Salary fields must round-trip through AES-GCM encryption and resist tampering. */
class EncryptedMoneyConverterTest {

    static EncryptedMoneyConverter converter;

    @BeforeAll
    static void setUp() {
        AppProperties props = new AppProperties();
        props.getEncryption().setKey("/v3o9AsnJZvJhjQHisPHU3HkESLK1ZESD+MbPgEwGUU=");
        EncryptionKeyHolder holder = new EncryptionKeyHolder(props);
        holder.init();
        converter = new EncryptedMoneyConverter();
        EncryptedMoneyConverter.setHolder(holder);
    }

    @Test
    void roundTrip() {
        BigDecimal salary = new BigDecimal("123456.78");
        byte[] stored = converter.convertToDatabaseColumn(salary);
        assertNotEquals(salary.toPlainString(), new String(stored)); // not plaintext
        assertEquals(salary, converter.convertToEntityAttribute(stored));
    }

    @Test
    void nullStaysNull() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    void uniqueIvPerValue() {
        BigDecimal salary = new BigDecimal("1000.00");
        byte[] first = converter.convertToDatabaseColumn(salary);
        byte[] second = converter.convertToDatabaseColumn(salary);
        assertNotEquals(first, second); // fresh IV each time
    }

    @Test
    void tamperedCiphertextFails() {
        byte[] stored = converter.convertToDatabaseColumn(new BigDecimal("9999.99"));
        stored[stored.length - 1] ^= 1; // flip a bit in the auth tag
        assertThrows(IllegalStateException.class, () -> converter.convertToEntityAttribute(stored));
    }

    @Test
    void rejectsBadKeys() {
        assertThrows(IllegalStateException.class, () -> {
            AppProperties missing = new AppProperties();
            new EncryptionKeyHolder(missing).init();
        });
        assertThrows(IllegalStateException.class, () -> {
            AppProperties tooShort = new AppProperties();
            tooShort.getEncryption().setKey("c2hvcnQ=");
            new EncryptionKeyHolder(tooShort).init();
        });
    }
}

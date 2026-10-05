package com.empmg.platform.compensation.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Raise money math: percent, fixed, rounding, tier caps. */
class MoneyMathTest {

    @Test
    void percentRaise() {
        assertEquals(new BigDecimal("1100.00"), MoneyMath.raise(new BigDecimal("1000"), new BigDecimal("10"), null)
                .setScale(2, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void fixedRaise() {
        assertEquals(0, new BigDecimal("1250").compareTo(
                MoneyMath.raise(new BigDecimal("1000"), null, new BigDecimal("250"))));
    }

    @Test
    void percentAndFixedCombine() {
        assertEquals(new BigDecimal("1350.000000"),
                MoneyMath.raise(new BigDecimal("1000"), new BigDecimal("10"), new BigDecimal("250")));
    }

    @Test
    void roundingRules() {
        assertEquals("123.456", MoneyMath.round(new BigDecimal("123.456"), "NONE").toPlainString());
        assertEquals("123", MoneyMath.round(new BigDecimal("123.456"), "NEAREST_1").toPlainString());
        assertEquals("4120.00", MoneyMath.round(new BigDecimal("4123.40"), "NEAREST_10").toPlainString());
        assertEquals("4200.00", MoneyMath.round(new BigDecimal("4150"), "NEAREST_100").toPlainString());
    }

    @Test
    void capAtTierMax() {
        var capped = MoneyMath.computeRaise(new BigDecimal("1000"), new BigDecimal("50"), null,
                new BigDecimal("1200"), "CAP", "NONE");
        assertTrue(capped.capped());
        assertEquals("1200.00", capped.newSalary().toPlainString());
    }

    @Test
    void allowAboveTierMax() {
        var allowed = MoneyMath.computeRaise(new BigDecimal("1000"), new BigDecimal("50"), null,
                new BigDecimal("1200"), "ALLOW", "NONE");
        assertFalse(allowed.capped());
        assertEquals("1500.00", allowed.newSalary().toPlainString());
    }

    @Test
    void noTierMaxNeverCaps() {
        var out = MoneyMath.computeRaise(new BigDecimal("1000"), new BigDecimal("50"), null,
                null, "CAP", "NONE");
        assertFalse(out.capped());
        assertEquals("1500.00", out.newSalary().toPlainString());
    }

    @Test
    void percentAppliedIsComputed() {
        var out = MoneyMath.computeRaise(new BigDecimal("1000"), new BigDecimal("10"), null, null, "ALLOW", "NONE");
        assertEquals(new BigDecimal("10.0000"), out.percentApplied());
    }

    @Test
    void monthlyConversion() {
        assertEquals(0, new BigDecimal("10000.00").compareTo(MoneyMath.monthlyOf(new BigDecimal("120000"), "ANNUAL")));
        assertEquals(0, new BigDecimal("5000.00").compareTo(MoneyMath.monthlyOf(new BigDecimal("5000"), "MONTHLY")));
    }
}

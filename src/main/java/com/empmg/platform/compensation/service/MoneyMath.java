package com.empmg.platform.compensation.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure money math. All amounts are BigDecimal (NUMERIC in DB) — never floating point.
 * Unit-tested directly; used by raise preview/apply and reversal.
 */
public final class MoneyMath {

    private MoneyMath() {}

    private static final BigDecimal TEN = BigDecimal.TEN;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public record Outcome(BigDecimal newSalary, boolean capped, BigDecimal percentApplied) {}

    /** Computes the new salary for a percent raise, a fixed raise, or both combined. */
    public static BigDecimal raise(BigDecimal oldSalary, BigDecimal percent, BigDecimal fixedAmount) {
        BigDecimal result = oldSalary;
        if (percent != null) {
            result = result.add(oldSalary.multiply(percent).divide(HUNDRED, 6, RoundingMode.HALF_UP));
        }
        if (fixedAmount != null) {
            result = result.add(fixedAmount);
        }
        return result;
    }

    /** Applies the company rounding rule. */
    public static BigDecimal round(BigDecimal value, String roundingRule) {
        return switch (roundingRule == null ? "NONE" : roundingRule) {
            case "NEAREST_1" -> value.setScale(0, RoundingMode.HALF_UP);
            case "NEAREST_10" -> value.divide(TEN, 0, RoundingMode.HALF_UP).multiply(TEN).setScale(2);
            case "NEAREST_100" -> value.divide(HUNDRED, 0, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(2);
            default -> value;
        };
    }

    /**
     * Full outcome of one person's raise, including tier-cap handling.
     * capPolicy: CAP (limit at tier max) | ALLOW (let it exceed) | MOVE (salary as computed; caller reassigns tier).
     */
    public static Outcome computeRaise(BigDecimal oldSalary, BigDecimal percent, BigDecimal fixedAmount,
                                       BigDecimal tierMax, String capPolicy, String roundingRule) {
        BigDecimal computed = raise(oldSalary, percent, fixedAmount);
        boolean capped = false;
        if ("CAP".equals(capPolicy) && tierMax != null && computed.compareTo(tierMax) > 0) {
            computed = tierMax;
            capped = true;
        }
        computed = round(computed, roundingRule).setScale(2, RoundingMode.HALF_UP);
        BigDecimal percentApplied = oldSalary.signum() == 0
                ? null
                : computed.subtract(oldSalary).divide(oldSalary, 4, RoundingMode.HALF_UP).multiply(HUNDRED);
        return new Outcome(computed, capped, percentApplied);
    }

    /** Monthly-equivalent of a salary, given the company's salary period. */
    public static BigDecimal monthlyOf(BigDecimal salary, String salaryPeriod) {
        return "ANNUAL".equals(salaryPeriod)
                ? salary.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP)
                : salary;
    }

    /** Annual-equivalent of a salary. */
    public static BigDecimal annualOf(BigDecimal monthlySalary) {
        return monthlySalary.multiply(BigDecimal.valueOf(12)).setScale(2, RoundingMode.HALF_UP);
    }
}

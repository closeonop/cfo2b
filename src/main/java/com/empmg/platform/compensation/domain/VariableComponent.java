package com.empmg.platform.compensation.domain;

import com.empmg.platform.common.EncryptedMoneyConverter;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A variable pay component on one employee: Bonus, Commission, Overtime, Deduction, or custom. */
@Entity
@Table(name = "variable_components")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VariableComponent {

    public static final String FIXED = "FIXED";       // value = amount
    public static final String PERCENT = "PERCENT";   // value = percent of base salary
    public static final String TARGET = "TARGET";     // value = target-based amount

    public static final String MONTHLY = "MONTHLY";
    public static final String QUARTERLY = "QUARTERLY";
    public static final String YEARLY = "YEARLY";
    public static final String ONE_TIME = "ONE_TIME";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "calc_type", nullable = false, length = 20)
    private String calcType;

    @Convert(converter = EncryptedMoneyConverter.class)
    @Column(nullable = false, columnDefinition = "bytea")
    private BigDecimal value;

    @Column(nullable = false, length = 20)
    private String period;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Monthly-equivalent amount of this component (one-time items are excluded by callers). */
    public BigDecimal monthlyEquivalent() {
        BigDecimal v = value;
        if (PERCENT.equals(calcType)) {
            return v; // percent is converted by the caller who knows the base salary
        }
        return switch (period) {
            case QUARTERLY -> v.divide(new BigDecimal("3"), 2, java.math.RoundingMode.HALF_UP);
            case YEARLY -> v.divide(new BigDecimal("12"), 2, java.math.RoundingMode.HALF_UP);
            default -> v; // MONTHLY
        };
    }
}

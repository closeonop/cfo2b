package com.empmg.platform.compensation.domain;

import com.empmg.platform.common.EncryptedMoneyConverter;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Append-only. Never updated or deleted; mistakes are fixed with reversing entries. */
@Entity
@Table(name = "salary_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalaryHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Convert(converter = EncryptedMoneyConverter.class)
    @Column(name = "old_salary", nullable = false, columnDefinition = "bytea")
    private BigDecimal oldSalary;

    @Convert(converter = EncryptedMoneyConverter.class)
    @Column(name = "new_salary", nullable = false, columnDefinition = "bytea")
    private BigDecimal newSalary;

    @Column(precision = 7, scale = 4)
    private BigDecimal percent;

    @Column(length = 500)
    private String reason;

    @Column(name = "changed_by_user_id")
    private Long changedByUserId;

    @Column(name = "reversal_of_id")
    private Long reversalOfId;

    @Column(name = "raise_id")
    private Long raiseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

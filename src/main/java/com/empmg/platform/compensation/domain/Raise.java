package com.empmg.platform.compensation.domain;

import com.empmg.platform.common.EncryptedMoneyConverter;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "raises")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Raise {

    public static final String PENDING = "PENDING";     // proposed (e.g. by HR), waiting for CFO
    public static final String APPROVED = "APPROVED";   // approved, waiting for effective date
    public static final String REJECTED = "REJECTED";
    public static final String APPLIED = "APPLIED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(precision = 7, scale = 4)
    private BigDecimal percent;

    @Convert(converter = EncryptedMoneyConverter.class)
    @Column(name = "fixed_amount", columnDefinition = "bytea")
    private BigDecimal fixedAmount;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(nullable = false, length = 500)
    private String reason;

    /** Human description of who was included, e.g. "Tier 2 — 12 people". */
    @Column(name = "scope_note", length = 500)
    private String scopeNote;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "approved_by_user_id")
    private Long approvedByUserId;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "capped_count", nullable = false)
    private int cappedCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

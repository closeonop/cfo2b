package com.empmg.platform.employee.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "job_roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "default_tier_id")
    private Long defaultTierId;

    @Column(name = "min_salary", precision = 14, scale = 2)
    private BigDecimal minSalary;

    @Column(name = "max_salary", precision = 14, scale = 2)
    private BigDecimal maxSalary;

    /** JSON array of default variable-pay component templates: [{name, calcType, value, period}] */
    @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "default_components", columnDefinition = "jsonb")
    private String defaultComponents;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

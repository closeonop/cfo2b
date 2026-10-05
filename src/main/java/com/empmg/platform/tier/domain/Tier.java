package com.empmg.platform.tier.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "tiers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "min_salary", precision = 14, scale = 2)
    private BigDecimal minSalary;

    @Column(name = "max_salary", precision = 14, scale = 2)
    private BigDecimal maxSalary;

    @Column(length = 20)
    private String color;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

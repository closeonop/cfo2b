package com.empmg.platform.company.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "companies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 10)
    private String locale;

    /** MONTHLY or ANNUAL — how salary amounts are expressed. */
    @Column(name = "salary_period", nullable = false, length = 10)
    private String salaryPeriod;

    /** NONE | NEAREST_1 | NEAREST_10 | NEAREST_100 */
    @Column(name = "rounding_rule", nullable = false, length = 10)
    private String roundingRule;

    @Column(name = "hr_can_see_salary", nullable = false)
    private boolean hrCanSeeSalary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private java.time.Instant createdAt;
}

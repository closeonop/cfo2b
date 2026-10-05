package com.empmg.platform.employee.domain;

import com.empmg.platform.common.EncryptedMoneyConverter;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "employees")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employee {

    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "employee_code", length = 40)
    private String employeeCode;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(length = 255)
    private String email;

    @Column(length = 40)
    private String phone;

    @Column(name = "job_role_id")
    private Long jobRoleId;

    @Column(name = "tier_id")
    private Long tierId;

    @Column(name = "manager_id")
    private Long managerId;

    @Column(length = 120)
    private String team;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(nullable = false, length = 20)
    private String status;

    /** Monthly or annual base salary (see company.salary_period). Encrypted at rest. */
    @Convert(converter = EncryptedMoneyConverter.class)
    @Column(name = "base_salary", nullable = false, columnDefinition = "bytea")
    private BigDecimal baseSalary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String fullName() {
        return firstName + " " + lastName;
    }
}

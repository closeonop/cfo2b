package com.empmg.platform.employee;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Employee as shown in the UI. baseSalary is null when the caller isn't allowed
 * to see it (server decides — never trust the client to hide fields).
 */
public record EmployeeDto(
        Long id,
        String employeeCode,
        String firstName,
        String lastName,
        String email,
        String phone,
        Long jobRoleId,
        String jobRole,
        Long tierId,
        String tier,
        BigDecimal tierMin,
        BigDecimal tierMax,
        String tierColor,
        Long managerId,
        String manager,
        String team,
        LocalDate startDate,
        String status,
        BigDecimal baseSalary,
        String salaryOutsideTier) {

    public String fullName() {
        return firstName + " " + lastName;
    }
}

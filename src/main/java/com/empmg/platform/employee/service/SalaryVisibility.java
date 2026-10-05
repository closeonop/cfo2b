package com.empmg.platform.employee.service;

import com.empmg.platform.common.ApiException;
import com.empmg.platform.company.domain.Company;
import com.empmg.platform.company.repo.CompanyRepo;
import com.empmg.platform.security.AccessRole;
import com.empmg.platform.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Central salary-visibility rules. Every compensation read/write goes through here. */
@Service
@RequiredArgsConstructor
public class SalaryVisibility {

    private final CompanyRepo companyRepo;

    public Company company(Long id) {
        return companyRepo.findById(id).orElseThrow(() -> new ApiException("Company not found.", 404));
    }

    /** Can this actor see salaries across the company? (CFO/Admin always; HR only when enabled) */
    public boolean canSeeSalaries(AuthPrincipal p, Company c) {
        return p.canSeeAllSalaries() || (p.role() == AccessRole.HR && c.isHrCanSeeSalary());
    }

    public boolean canSeeSalaries(AuthPrincipal p) {
        return canSeeSalaries(p, company(p.companyId()));
    }

    /** Can this actor change compensation (salaries, variable pay, raises applied)? Admin/CFO only. */
    public boolean canChangeSalaries(AuthPrincipal p) {
        return p.role() == AccessRole.ADMIN || p.role() == AccessRole.CFO;
    }

    public void assertCanSeeSalaries(AuthPrincipal p) {
        if (!canSeeSalaries(p)) {
            throw new ApiException("You don't have permission to see salary information.", 403);
        }
    }

    public void assertCanChangeSalaries(AuthPrincipal p) {
        if (!canChangeSalaries(p)) {
            throw new ApiException("Only Admin or Finance can change salary information.", 403);
        }
    }
}

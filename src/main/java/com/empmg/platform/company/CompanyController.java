package com.empmg.platform.company;

import com.empmg.platform.audit.service.AuditService;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.company.domain.Company;
import com.empmg.platform.company.repo.CompanyRepo;
import com.empmg.platform.security.AuthPrincipal;
import com.empmg.platform.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/company")
@RequiredArgsConstructor
public class CompanyController {

    private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "GBP", "INR", "AED", "CAD", "AUD", "SGD");
    private static final Set<String> LOCALES = Set.of("en-US", "en-IN", "en-GB", "de-DE", "fr-FR");
    private static final Set<String> PERIODS = Set.of("MONTHLY", "ANNUAL");
    private static final Set<String> ROUNDING = Set.of("NONE", "NEAREST_1", "NEAREST_10", "NEAREST_100");

    private final CompanyRepo companyRepo;
    private final AuditService audit;

    public record SettingsDto(
            @NotBlank String name,
            @NotBlank String currency,
            @NotBlank String locale,
            @NotBlank String salaryPeriod,
            @NotBlank String roundingRule,
            boolean hrCanSeeSalary) {}

    @GetMapping
    public Map<String, Object> get() {
        AuthPrincipal p = SecurityUtils.current();
        Company c = companyRepo.findById(p.companyId())
                .orElseThrow(() -> new ApiException("Company not found.", 404));
        return Map.of(
                "id", c.getId(),
                "name", c.getName(),
                "currency", c.getCurrency(),
                "locale", c.getLocale(),
                "salaryPeriod", c.getSalaryPeriod(),
                "roundingRule", c.getRoundingRule(),
                "hrCanSeeSalary", c.isHrCanSeeSalary());
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','CFO')")
    @Transactional
    public Map<String, Object> update(@RequestBody SettingsDto dto) {
        AuthPrincipal p = SecurityUtils.current();
        Company c = companyRepo.findById(p.companyId())
                .orElseThrow(() -> new ApiException("Company not found.", 404));
        if (!CURRENCIES.contains(dto.currency())) throw new ApiException("That currency isn't supported yet.");
        if (!LOCALES.contains(dto.locale())) throw new ApiException("That locale isn't supported yet.");
        if (!PERIODS.contains(dto.salaryPeriod())) throw new ApiException("Salary period must be monthly or annual.");
        if (!ROUNDING.contains(dto.roundingRule())) throw new ApiException("That rounding rule isn't valid.");

        c.setName(dto.name().trim());
        c.setCurrency(dto.currency());
        c.setLocale(dto.locale());
        c.setSalaryPeriod(dto.salaryPeriod());
        c.setRoundingRule(dto.roundingRule());
        c.setHrCanSeeSalary(dto.hrCanSeeSalary());
        companyRepo.save(c);
        audit.record(p, p.companyId(), "SETTINGS_UPDATED", "COMPANY", c.getId(), Map.of());
        return get();
    }

    /** The built-in access-role permission matrix, rendered in Settings. */
    @GetMapping("/permissions")
    public List<Map<String, Object>> permissions() {
        return List.of(
                Map.of("area", "Company settings & policies", "ADMIN", true, "CFO", true, "HR", false, "MANAGER", false, "EMPLOYEE", false),
                Map.of("area", "Salaries, tiers & raises", "ADMIN", true, "CFO", true, "HR", false, "MANAGER", false, "EMPLOYEE", false),
                Map.of("area", "Propose a raise", "ADMIN", true, "CFO", true, "HR", true, "MANAGER", false, "EMPLOYEE", false),
                Map.of("area", "Employee profiles", "ADMIN", true, "CFO", true, "HR", true, "MANAGER", "Own team", "EMPLOYEE", "Own"),
                Map.of("area", "Salary visibility", "ADMIN", true, "CFO", true, "HR", "If enabled", "MANAGER", false, "EMPLOYEE", "Own only"),
                Map.of("area", "Audit log", "ADMIN", true, "CFO", false, "HR", false, "MANAGER", false, "EMPLOYEE", false),
                Map.of("area", "Import & export employees", "ADMIN", true, "CFO", true, "HR", false, "MANAGER", false, "EMPLOYEE", false));
    }
}

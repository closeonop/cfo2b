package com.empmg.platform.compensation;

import com.empmg.platform.audit.service.AuditService;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.compensation.domain.VariableComponent;
import com.empmg.platform.compensation.repo.VariableComponentRepo;
import com.empmg.platform.employee.domain.Employee;
import com.empmg.platform.employee.repo.EmployeeRepo;
import com.empmg.platform.employee.service.SalaryVisibility;
import com.empmg.platform.security.AuthPrincipal;
import com.empmg.platform.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Variable pay components per employee: Bonus, Commission, Overtime, Deduction, or custom. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class VariableComponentController {

    private final VariableComponentRepo componentRepo;
    private final EmployeeRepo employeeRepo;
    private final SalaryVisibility salaryVisibility;
    private final AuditService audit;

    public record ComponentDto(Long id, Long employeeId, String name, String calcType, BigDecimal value,
                               String period, LocalDate startDate, LocalDate endDate, boolean active) {
        static ComponentDto of(VariableComponent c) {
            return new ComponentDto(c.getId(), c.getEmployeeId(), c.getName(), c.getCalcType(), c.getValue(),
                    c.getPeriod(), c.getStartDate(), c.getEndDate(), c.isActive());
        }
    }

    public record ComponentInput(@NotBlank String name, @NotBlank String calcType, @NotBlank BigDecimal value,
                                 @NotBlank String period, LocalDate startDate, LocalDate endDate) {}

    @GetMapping("/employees/{employeeId}/components")
    public List<ComponentDto> list(@PathVariable Long employeeId) {
        AuthPrincipal p = SecurityUtils.current();
        boolean self = p.employeeId() != null && p.employeeId().equals(employeeId);
        if (!self) {
            salaryVisibility.assertCanSeeSalaries(p);
        }
        employeeRepo.findByIdAndCompanyId(employeeId, p.companyId())
                .orElseThrow(() -> new ApiException("Employee not found.", 404));
        audit.record(p, p.companyId(), "COMPONENTS_VIEWED", "EMPLOYEE", employeeId, Map.of());
        return componentRepo.findByCompanyIdAndEmployeeIdOrderByIdAsc(p.companyId(), employeeId).stream()
                .map(ComponentDto::of)
                .toList();
    }

    @PostMapping("/employees/{employeeId}/components")
    @PreAuthorize("hasAnyRole('ADMIN','CFO')")
    @Transactional
    public ComponentDto create(@PathVariable Long employeeId, @RequestBody ComponentInput in) {
        AuthPrincipal p = SecurityUtils.current();
        Employee e = employeeRepo.findByIdAndCompanyId(employeeId, p.companyId())
                .orElseThrow(() -> new ApiException("Employee not found.", 404));
        validate(in);
        VariableComponent c = componentRepo.save(VariableComponent.builder()
                .companyId(p.companyId())
                .employeeId(e.getId())
                .name(in.name().trim())
                .calcType(in.calcType())
                .value(in.value())
                .period(in.period())
                .startDate(in.startDate())
                .endDate(in.endDate())
                .active(true)
                .createdAt(Instant.now())
                .build());
        audit.record(p, p.companyId(), "COMPONENT_CREATED", "VARIABLE_COMPONENT", c.getId(),
                Map.of("employeeId", employeeId, "name", c.getName()));
        return ComponentDto.of(c);
    }

    @PutMapping("/components/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CFO')")
    @Transactional
    public ComponentDto update(@PathVariable Long id, @RequestBody ComponentInput in) {
        AuthPrincipal p = SecurityUtils.current();
        validate(in);
        VariableComponent c = componentRepo.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> new ApiException("Component not found.", 404));
        c.setName(in.name().trim());
        c.setCalcType(in.calcType());
        c.setValue(in.value());
        c.setPeriod(in.period());
        c.setStartDate(in.startDate());
        c.setEndDate(in.endDate());
        componentRepo.save(c);
        audit.record(p, p.companyId(), "COMPONENT_UPDATED", "VARIABLE_COMPONENT", c.getId(),
                Map.of("name", c.getName()));
        return ComponentDto.of(c);
    }

    @DeleteMapping("/components/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CFO')")
    @Transactional
    public Map<String, Object> remove(@PathVariable Long id) {
        AuthPrincipal p = SecurityUtils.current();
        VariableComponent c = componentRepo.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> new ApiException("Component not found.", 404));
        componentRepo.delete(c);
        audit.record(p, p.companyId(), "COMPONENT_DELETED", "VARIABLE_COMPONENT", c.getId(),
                Map.of("name", c.getName(), "employeeId", c.getEmployeeId()));
        return Map.of("message", "Component removed.");
    }

    private void validate(ComponentInput in) {
        if (!List.of("FIXED", "PERCENT", "TARGET").contains(in.calcType())) {
            throw new ApiException("The calculation type must be a fixed amount, a percent of base salary, or a target amount.");
        }
        if (!List.of("MONTHLY", "QUARTERLY", "YEARLY", "ONE_TIME").contains(in.period())) {
            throw new ApiException("The period must be monthly, quarterly, yearly, or one-time.");
        }
        if (in.value().signum() < 0) {
            throw new ApiException("The value can't be negative.");
        }
        if ("PERCENT".equals(in.calcType()) && in.value().compareTo(BigDecimal.valueOf(1000)) > 0) {
            throw new ApiException("A percent can't be more than 1000.");
        }
    }
}

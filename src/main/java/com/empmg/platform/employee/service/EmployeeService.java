package com.empmg.platform.employee.service;

import com.empmg.platform.audit.service.AuditService;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.compensation.domain.SalaryHistory;
import com.empmg.platform.compensation.repo.SalaryHistoryRepo;
import com.empmg.platform.company.domain.Company;
import com.empmg.platform.employee.EmployeeDto;
import com.empmg.platform.employee.domain.Employee;
import com.empmg.platform.employee.repo.EmployeeRepo;
import com.empmg.platform.employee.repo.JobRoleRepo;
import com.empmg.platform.employee.domain.JobRole;
import com.empmg.platform.security.AccessRole;
import com.empmg.platform.security.AuthPrincipal;
import com.empmg.platform.tier.domain.Tier;
import com.empmg.platform.tier.repo.TierRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepo employeeRepo;
    private final TierRepo tierRepo;
    private final JobRoleRepo jobRoleRepo;
    private final SalaryHistoryRepo salaryHistoryRepo;
    private final SalaryVisibility salaryVisibility;
    private final AuditService audit;

    // ---------- reading ----------

    /** Employees visible to this actor: everyone for Admin/CFO/HR, own team for managers, self for employees. */
    @Transactional(readOnly = true)
    public List<EmployeeDto> list(AuthPrincipal p) {
        Company company = salaryVisibility.company(p.companyId());
        List<Employee> all = employeeRepo.findByCompanyIdOrderByFirstNameAscLastNameAsc(p.companyId());
        List<Employee> scoped = switch (p.role()) {
            case ADMIN, CFO, HR -> all;
            case MANAGER -> p.employeeId() == null ? List.of()
                    : all.stream().filter(e -> p.employeeId().equals(e.getManagerId())||p.employeeId().equals(e.getId())).toList();
            case EMPLOYEE -> p.employeeId() == null ? List.of()
                    : all.stream().filter(e -> p.employeeId().equals(e.getId())).toList();
        };
        boolean seeSalary = salaryVisibility.canSeeSalaries(p, company);
        return toDtos(scoped, p.companyId(), seeSalary);
    }

    @Transactional(readOnly = true)
    public EmployeeDto get(AuthPrincipal p, Long id) {
        Company company = salaryVisibility.company(p.companyId());
        Employee e = employeeRepo.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> new ApiException("Employee not found.", 404));
        assertCanViewEmployee(p, e);
        boolean seeSalary = salaryVisibility.canSeeSalaries(p, company);
        return toDtos(List.of(e), p.companyId(), seeSalary).get(0);
    }

    /** Managers see their own team's profiles (without salaries); employees see only themselves. */
    public void assertCanViewEmployee(AuthPrincipal p, Employee e) {
        switch (p.role()) {
            case ADMIN, CFO, HR -> {}
            case MANAGER -> {
                boolean ownTeam = p.employeeId() != null
                        && (p.employeeId().equals(e.getManagerId()) || p.employeeId().equals(e.getId()));
                if (!ownTeam) {
                    throw new ApiException("You can only view people in your team.", 403);
                }
            }
            case EMPLOYEE -> {
                if (p.employeeId() == null || !p.employeeId().equals(e.getId())) {
                    throw new ApiException("You can only view your own profile.", 403);
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public List<String> teams(AuthPrincipal p) {
        return employeeRepo.findDistinctTeams(p.companyId());
    }

    private List<EmployeeDto> toDtos(List<Employee> scoped, Long companyId, boolean seeSalary) {
        Map<Long, Tier> tiers = tierRepo.findByCompanyIdOrderBySortOrderAscIdAsc(companyId)
                .stream().collect(Collectors.toMap(Tier::getId, Function.identity()));
        Map<Long, JobRole> roles = jobRoleRepo.findByCompanyIdAndArchivedFalseOrderByNameAsc(companyId)
                .stream().collect(Collectors.toMap(JobRole::getId, Function.identity()));
        Map<Long, Employee> byId = employeeRepo.findByCompanyIdOrderByFirstNameAscLastNameAsc(companyId)
                .stream().collect(Collectors.toMap(Employee::getId, Function.identity()));

        List<EmployeeDto> out = new ArrayList<>(scoped.size());
        for (Employee e : scoped) {
            Tier tier = e.getTierId() != null ? tiers.get(e.getTierId()) : null;
            JobRole role = e.getJobRoleId() != null ? roles.get(e.getJobRoleId()) : null;
            Employee mgr = e.getManagerId() != null ? byId.get(e.getManagerId()) : null;
            BigDecimal salary = seeSalary ? e.getBaseSalary() : null;
            out.add(new EmployeeDto(
                    e.getId(), e.getEmployeeCode(), e.getFirstName(), e.getLastName(),
                    e.getEmail(), e.getPhone(),
                    role != null ? role.getId() : null, role != null ? role.getName() : null,
                    tier != null ? tier.getId() : null, tier != null ? tier.getName() : null,
                    tier != null ? tier.getMinSalary() : null, tier != null ? tier.getMaxSalary() : null,
                    tier != null ? tier.getColor() : null,
                    mgr != null ? mgr.getId() : null, mgr != null ? mgr.fullName() : null,
                    e.getTeam(), e.getStartDate(), e.getStatus(),
                    salary, outsideTier(salary, tier)));
        }
        return out;
    }

    private String outsideTier(BigDecimal salary, Tier tier) {
        if (salary == null || tier == null) return null;
        if (tier.getMaxSalary() != null && salary.compareTo(tier.getMaxSalary()) > 0) return "ABOVE";
        if (tier.getMinSalary() != null && salary.compareTo(tier.getMinSalary()) < 0) return "BELOW";
        return null;
    }

    // ---------- writing ----------

    public record EmployeeInput(
            String employeeCode, String firstName, String lastName, String email, String phone,
            Long jobRoleId, Long tierId, Long managerId, String team, LocalDate startDate, String status) {}

    @Transactional
    public EmployeeDto create(AuthPrincipal p, EmployeeInput in, BigDecimal salary, String salaryReason) {
        Company company = salaryVisibility.company(p.companyId());
        if (p.role() == AccessRole.MANAGER || p.role() == AccessRole.EMPLOYEE) {
            throw new ApiException("You don't have permission to add employees.", 403);
        }
        validateInput(p, in, null);
        if (salary == null) salary = BigDecimal.ZERO;
        if (salary.signum() != 0) salaryVisibility.assertCanChangeSalaries(p);
        if (salary.signum() < 0) {
            throw new ApiException("Please enter a salary of zero or more.");
        }
        Employee e = Employee.builder()
                .companyId(p.companyId())
                .employeeCode(in.employeeCode())
                .firstName(in.firstName().trim())
                .lastName(in.lastName().trim())
                .email(in.email() == null || in.email().isBlank() ? null : in.email().trim().toLowerCase())
                .phone(in.phone())
                .jobRoleId(in.jobRoleId())
                .tierId(in.tierId())
                .managerId(in.managerId())
                .team(in.team())
                .startDate(in.startDate())
                .status(in.status() == null ? Employee.ACTIVE : in.status())
                .baseSalary(salary)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        e = employeeRepo.save(e);

        salaryHistoryRepo.save(SalaryHistory.builder()
                .companyId(p.companyId()).employeeId(e.getId())
                .effectiveDate(in.startDate())
                .oldSalary(BigDecimal.ZERO).newSalary(salary)
                .reason(salaryReason == null || salaryReason.isBlank() ? "Initial salary" : salaryReason.trim())
                .changedByUserId(p.userId())
                .createdAt(Instant.now())
                .build());

        audit.record(p, p.companyId(), "EMPLOYEE_CREATED", "EMPLOYEE", e.getId(),
                Map.of("name", e.getFirstName() + " " + e.getLastName()));
        return get(p, e.getId());
    }

    @Transactional
    public EmployeeDto update(AuthPrincipal p, Long id, EmployeeInput in, BigDecimal salary, String salaryReason) {
        Company company = salaryVisibility.company(p.companyId());
        if (p.role() == AccessRole.MANAGER || p.role() == AccessRole.EMPLOYEE) {
            throw new ApiException("You don't have permission to edit employees.", 403);
        }
        Employee e = employeeRepo.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> new ApiException("Employee not found.", 404));
        validateInput(p, in, id);

        e.setEmployeeCode(in.employeeCode());
        e.setFirstName(in.firstName().trim());
        e.setLastName(in.lastName().trim());
        e.setEmail(in.email() == null || in.email().isBlank() ? null : in.email().trim().toLowerCase());
        e.setPhone(in.phone());
        e.setJobRoleId(in.jobRoleId());
        e.setTierId(in.tierId());
        e.setManagerId(in.managerId());
        e.setTeam(in.team());
        e.setStartDate(in.startDate());
        e.setStatus(in.status() == null ? e.getStatus() : in.status());
        e.setUpdatedAt(Instant.now());

        if (salary != null && salary.compareTo(e.getBaseSalary()) != 0) {
            salaryVisibility.assertCanChangeSalaries(p);
            if (salary.signum() < 0) throw new ApiException("Salary cannot be negative.");
            if (salaryReason == null || salaryReason.isBlank()) {
                throw new ApiException("Please give a reason for the salary change.");
            }
            BigDecimal old = e.getBaseSalary();
            e.setBaseSalary(salary);
            salaryHistoryRepo.save(SalaryHistory.builder()
                    .companyId(p.companyId()).employeeId(e.getId())
                    .effectiveDate(LocalDate.now())
                    .oldSalary(old).newSalary(salary)
                    .reason(salaryReason.trim())
                    .changedByUserId(p.userId())
                    .createdAt(Instant.now())
                    .build());
        }
        employeeRepo.save(e);
        audit.record(p, p.companyId(), "EMPLOYEE_UPDATED", "EMPLOYEE", e.getId(), Map.of());
        return get(p, e.getId());
    }

    @Transactional
    public EmployeeDto setStatus(AuthPrincipal p, Long id, String status) {
        if (p.role() == AccessRole.MANAGER || p.role() == AccessRole.EMPLOYEE) {
            throw new ApiException("You don't have permission to change employee status.", 403);
        }
        if (!Employee.ACTIVE.equals(status) && !Employee.INACTIVE.equals(status)) {
            throw new ApiException("Status must be ACTIVE or INACTIVE.");
        }
        Employee e = employeeRepo.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> new ApiException("Employee not found.", 404));
        e.setStatus(status);
        e.setUpdatedAt(Instant.now());
        employeeRepo.save(e);
        audit.record(p, p.companyId(), Employee.ACTIVE.equals(status) ? "EMPLOYEE_ACTIVATED" : "EMPLOYEE_DEACTIVATED",
                "EMPLOYEE", e.getId(), Map.of());
        return get(p, e.getId());
    }

    private void validateInput(AuthPrincipal p, EmployeeInput in, Long selfId) {
        if (in.status() != null && !Set.of(Employee.ACTIVE, Employee.INACTIVE).contains(in.status()))
            throw new ApiException("Status must be ACTIVE or INACTIVE.");
        if (in.firstName() == null || in.firstName().isBlank()) throw new ApiException("First name is required.");
        if (in.lastName() == null || in.lastName().isBlank()) throw new ApiException("Last name is required.");
        if (in.startDate() == null) throw new ApiException("Start date is required.");
        if (in.firstName().trim().length()>100 || in.lastName().trim().length()>100) throw new ApiException("Names must be at most 100 characters.");
        if (in.phone()!=null && in.phone().length()>40 || in.employeeCode()!=null && in.employeeCode().length()>40)
            throw new ApiException("Phone and employee code must be at most 40 characters.");
        if (in.team()!=null && in.team().length()>120) throw new ApiException("Team name must be at most 120 characters.");
        if (in.email() != null && !in.email().isBlank()) {
            String email = in.email().trim().toLowerCase();
            if(email.length()>255 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new ApiException("Enter a valid email of at most 255 characters.");
            employeeRepo.findByCompanyIdAndEmailIgnoreCase(p.companyId(), email)
                    .ifPresent(other -> {
                        if (!other.getId().equals(selfId)) {
                            throw new ApiException("That email is already used by another employee.");
                        }
                    });
        }
        if (in.managerId() != null) {
            Employee mgr = employeeRepo.findByIdAndCompanyId(in.managerId(), p.companyId())
                    .orElseThrow(() -> new ApiException("The selected manager doesn't exist."));
            if (mgr.getId().equals(selfId)) {
                throw new ApiException("An employee can't be their own manager.");
            }
            Set<Long> seen = new HashSet<>();
            for (Employee ancestor = mgr; ancestor != null; ancestor = ancestor.getManagerId() == null ? null
                    : employeeRepo.findByIdAndCompanyId(ancestor.getManagerId(), p.companyId()).orElse(null)) {
                if (ancestor.getId().equals(selfId) || !seen.add(ancestor.getId()))
                    throw new ApiException("This manager would create a reporting loop.");
            }
        }
        if (in.tierId() != null && tierRepo.findByIdAndCompanyId(in.tierId(), p.companyId()).isEmpty()) {
            throw new ApiException("The selected tier doesn't exist.");
        }
        if (in.jobRoleId() != null && jobRoleRepo.findByIdAndCompanyId(in.jobRoleId(), p.companyId()).isEmpty()) {
            throw new ApiException("The selected job role doesn't exist.");
        }
    }
}

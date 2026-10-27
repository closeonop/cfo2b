package com.empmg.platform.tier;

import com.empmg.platform.audit.service.AuditService;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.employee.domain.Employee;
import com.empmg.platform.employee.repo.EmployeeRepo;
import com.empmg.platform.employee.service.SalaryVisibility;
import com.empmg.platform.security.AuthPrincipal;
import com.empmg.platform.security.SecurityUtils;
import com.empmg.platform.tier.domain.Tier;
import com.empmg.platform.tier.repo.TierRepo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.empmg.platform.compensation.service.MoneyMath;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tiers")
@RequiredArgsConstructor
public class TierController {

    private final TierRepo tierRepo;
    private final EmployeeRepo employeeRepo;
    private final SalaryVisibility salaryVisibility;
    private final AuditService audit;

    public record TierDto(Long id, String name, String description, BigDecimal minSalary, BigDecimal maxSalary,
                          String color, int sortOrder, boolean archived) {
        static TierDto of(Tier t) {
            return new TierDto(t.getId(), t.getName(), t.getDescription(), t.getMinSalary(), t.getMaxSalary(),
                    t.getColor(), t.getSortOrder(), t.isArchived());
        }
    }

    public record TierInput(@NotBlank @Size(max=120) String name, @Size(max=500) String description,
                            @DecimalMin("0") @Digits(integer=12, fraction=2) BigDecimal minSalary,
                            @DecimalMin("0") @Digits(integer=12, fraction=2) BigDecimal maxSalary,
                            @Pattern(regexp="#[0-9a-fA-F]{6}") String color) {}

    public record AssignRequest(List<Long> employeeIds, Long tierId) {}

    public record TierStats(Long id, String name, String color, long headcount, BigDecimal totalMonthlyCost,
                            BigDecimal averageSalary, BigDecimal minSalary, BigDecimal maxSalary) {}

    // ---------- CRUD ----------

    @GetMapping
    public List<TierDto> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        AuthPrincipal p = SecurityUtils.current();
        return tierRepo.findByCompanyIdOrderBySortOrderAscIdAsc(p.companyId()).stream()
                .filter(t -> includeArchived || !t.isArchived())
                .map(TierDto::of)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','CFO')")
    @Transactional
    public TierDto create(@Valid @RequestBody TierInput in) {
        AuthPrincipal p = SecurityUtils.current();
        validate(in);
        if (tierRepo.existsByCompanyIdAndNameIgnoreCaseAndArchivedFalse(p.companyId(), in.name().trim())) {
            throw new ApiException("A tier with that name already exists.");
        }
        int next = tierRepo.findByCompanyIdOrderBySortOrderAscIdAsc(p.companyId()).size();
        Tier t = tierRepo.save(Tier.builder()
                .companyId(p.companyId())
                .name(in.name().trim())
                .description(in.description())
                .minSalary(in.minSalary())
                .maxSalary(in.maxSalary())
                .color(in.color())
                .sortOrder(next)
                .archived(false)
                .createdAt(Instant.now())
                .build());
        audit.record(p, p.companyId(), "TIER_CREATED", "TIER", t.getId(), Map.of("name", t.getName()));
        return TierDto.of(t);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CFO')")
    @Transactional
    public TierDto update(@PathVariable Long id, @Valid @RequestBody TierInput in) {
        AuthPrincipal p = SecurityUtils.current();
        validate(in);
        Tier t = tierRepo.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> new ApiException("Tier not found.", 404));
        tierRepo.findByCompanyIdOrderBySortOrderAscIdAsc(p.companyId()).stream()
                .filter(other -> !other.isArchived() && other.getName().equalsIgnoreCase(in.name().trim()) && !other.getId().equals(id))
                .findFirst()
                .ifPresent(other -> { throw new ApiException("A tier with that name already exists."); });
        t.setName(in.name().trim());
        t.setDescription(in.description());
        t.setMinSalary(in.minSalary());
        t.setMaxSalary(in.maxSalary());
        t.setColor(in.color());
        tierRepo.save(t);
        audit.record(p, p.companyId(), "TIER_UPDATED", "TIER", t.getId(), Map.of("name", t.getName()));
        return TierDto.of(t);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('ADMIN','CFO')")
    @Transactional
    public TierDto archive(@PathVariable Long id) {
        AuthPrincipal p = SecurityUtils.current();
        Tier t = tierRepo.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> new ApiException("Tier not found.", 404));
        // tiers with employees are archived, never deleted — history stays intact
        if (t.isArchived() && tierRepo.existsByCompanyIdAndNameIgnoreCaseAndArchivedFalse(p.companyId(), t.getName()))
            throw new ApiException("An active band already uses this name. Rename it before restoring this band.");
        t.setArchived(!t.isArchived());
        tierRepo.save(t);
        audit.record(p, p.companyId(), t.isArchived() ? "TIER_ARCHIVED" : "TIER_RESTORED", "TIER", t.getId(),
                Map.of("name", t.getName()));
        return TierDto.of(t);
    }

    @PutMapping("/reorder")
    @PreAuthorize("hasAnyRole('ADMIN','CFO')")
    @Transactional
    public List<TierDto> reorder(@RequestBody List<Long> ids) {
        AuthPrincipal p = SecurityUtils.current();
        List<Tier> tiers = tierRepo.findByCompanyIdOrderBySortOrderAscIdAsc(p.companyId());
        var expected = tiers.stream().filter(t -> !t.isArchived()).map(Tier::getId).collect(java.util.stream.Collectors.toSet());
        if (ids == null || ids.contains(null) || ids.size() != expected.size() || !new java.util.HashSet<>(ids).equals(expected))
            throw new ApiException("Include every active pay band exactly once when reordering.");
        int order = 0;
        for (Long id : ids) {
            for (Tier t : tiers) {
                if (t.getId().equals(id)) {
                    t.setSortOrder(order++);
                }
            }
        }
        tierRepo.saveAll(tiers);
        audit.record(p, p.companyId(), "TIERS_REORDERED", "TIER", null, Map.of());
        return list(false);
    }

    // ---------- assignment & stats ----------

    @PostMapping("/assign")
    @PreAuthorize("hasAnyRole('ADMIN','CFO','HR')")
    @Transactional
    public Map<String, Object> assign(@RequestBody AssignRequest req) {
        AuthPrincipal p = SecurityUtils.current();
        if (req.employeeIds() == null || req.employeeIds().isEmpty()) {
            throw new ApiException("Select at least one employee to move.");
        }
        if (req.tierId() != null && tierRepo.findByIdAndCompanyId(req.tierId(), p.companyId()).filter(t -> !t.isArchived()).isEmpty()) {
            throw new ApiException("That tier doesn't exist.");
        }
        if (req.employeeIds().contains(null) || new java.util.HashSet<>(req.employeeIds()).size() != req.employeeIds().size())
            throw new ApiException("Select each employee once.");
        for (Long id : req.employeeIds()) {
            if (employeeRepo.findByIdAndCompanyId(id, p.companyId()).filter(e -> Employee.ACTIVE.equals(e.getStatus())).isEmpty())
                throw new ApiException("Select active employees from your company.");
        }
        int moved = 0;
        for (Long id : req.employeeIds()) {
            var e = employeeRepo.findByIdAndCompanyId(id, p.companyId());
            if (e.isPresent() && !java.util.Objects.equals(e.get().getTierId(), req.tierId())) {
                Employee emp = e.get();
                emp.setTierId(req.tierId());
                emp.setUpdatedAt(Instant.now());
                employeeRepo.save(emp);
                moved++;
            }
        }
        audit.record(p, p.companyId(), "EMPLOYEES_TIER_ASSIGNED", "TIER", req.tierId(), Map.of("moved", moved));
        return Map.of("moved", moved);
    }

    /** Per-tier headcount and cost. Salary figures require salary visibility. */
    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('ADMIN','CFO','HR')")
    public List<TierStats> stats() {
        AuthPrincipal p = SecurityUtils.current();
        boolean seeSalary = salaryVisibility.canSeeSalaries(p);
        List<Tier> tiers = tierRepo.findByCompanyIdOrderBySortOrderAscIdAsc(p.companyId());
        List<Employee> employees = employeeRepo.findByCompanyIdOrderByFirstNameAscLastNameAsc(p.companyId());
        String period = salaryVisibility.company(p.companyId()).getSalaryPeriod();
        List<TierStats> out = new ArrayList<>();
        for (Tier t : tiers) {
            List<Employee> inTier = employees.stream()
                    .filter(e -> t.getId().equals(e.getTierId()) && Employee.ACTIVE.equals(e.getStatus()))
                    .toList();
            BigDecimal total = BigDecimal.ZERO, monthly = BigDecimal.ZERO, min = null, max = null;
            for (Employee e : inTier) {
                BigDecimal s = e.getBaseSalary();
                total = total.add(s);
                monthly = monthly.add(MoneyMath.monthlyOf(s, period));
                min = min == null || s.compareTo(min) < 0 ? s : min;
                max = max == null || s.compareTo(max) > 0 ? s : max;
            }
            BigDecimal avg = inTier.isEmpty() ? BigDecimal.ZERO
                    : total.divide(BigDecimal.valueOf(inTier.size()), 2, RoundingMode.HALF_UP);
            out.add(new TierStats(t.getId(), t.getName(), t.getColor(), inTier.size(),
                    seeSalary ? monthly : null, seeSalary ? avg : null,
                    seeSalary ? min : null, seeSalary ? max : null));
        }
        return out;
    }

    /** Suggests the tier whose salary range contains the given salary. */
    @GetMapping("/suggest")
    public Map<String, Object> suggest(@RequestParam BigDecimal salary) {
        AuthPrincipal p = SecurityUtils.current();
        return tierRepo.findByCompanyIdAndArchivedFalseOrderBySortOrderAscIdAsc(p.companyId()).stream()
                .filter(t -> (t.getMinSalary() == null || salary.compareTo(t.getMinSalary()) >= 0)
                        && (t.getMaxSalary() == null || salary.compareTo(t.getMaxSalary()) <= 0))
                .findFirst()
                .map(t -> Map.<String, Object>of("id", (Object) t.getId(), "name", (Object) t.getName()))
                .orElseGet(Map::of);
    }

    private void validate(TierInput in) {
        if (in.minSalary() != null && in.maxSalary() != null && in.minSalary().compareTo(in.maxSalary()) > 0) {
            throw new ApiException("The minimum salary can't be higher than the maximum salary.");
        }
        if (in.minSalary() != null && in.minSalary().signum() < 0
                || in.maxSalary() != null && in.maxSalary().signum() < 0) {
            throw new ApiException("Salary ranges can't be negative.");
        }
    }
}

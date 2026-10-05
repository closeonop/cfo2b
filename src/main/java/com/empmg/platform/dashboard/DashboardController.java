package com.empmg.platform.dashboard;

import com.empmg.platform.audit.domain.AuditLog;
import com.empmg.platform.audit.repo.AuditLogRepo;
import com.empmg.platform.compensation.domain.VariableComponent;
import com.empmg.platform.compensation.repo.RaiseRepo;
import com.empmg.platform.compensation.repo.VariableComponentRepo;
import com.empmg.platform.compensation.service.CompensationService;
import com.empmg.platform.compensation.service.MoneyMath;
import com.empmg.platform.company.repo.CompanyRepo;
import com.empmg.platform.employee.domain.Employee;
import com.empmg.platform.employee.repo.EmployeeRepo;
import com.empmg.platform.employee.service.SalaryVisibility;
import com.empmg.platform.security.AuthPrincipal;
import com.empmg.platform.security.SecurityUtils;
import com.empmg.platform.tier.repo.TierRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Dashboard: at most five things — total employees, total monthly payroll cost,
 * headcount and cost by tier, upcoming raises, recent changes.
 * Money figures are null when the viewer isn't allowed to see them.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final EmployeeRepo employeeRepo;
    private final TierRepo tierRepo;
    private final RaiseRepo raiseRepo;
    private final VariableComponentRepo componentRepo;
    private final CompanyRepo companyRepo;
    private final AuditLogRepo auditRepo;
    private final CompensationService compensationService;
    private final SalaryVisibility salaryVisibility;

    public record Dashboard(
            int totalEmployees,
            BigDecimal totalMonthlyCost,
            List<TierCard> byTier,
            List<UpcomingRaise> upcomingRaises,
            List<RecentChange> recentChanges) {}

    public record TierCard(Long id, String name, String color, long headcount, BigDecimal monthlyCost) {}

    public record UpcomingRaise(Long id, String status, LocalDate effectiveDate, String scopeNote,
                                int affected, BigDecimal monthlyDelta) {}

    public record RecentChange(String action, String actor, Instant at, String detail) {}

    @GetMapping
    @Transactional(readOnly = true)
    public Dashboard get() {
        AuthPrincipal p = SecurityUtils.current();
        boolean seeMoney = salaryVisibility.canSeeSalaries(p);
        String period = salaryVisibility.company(p.companyId()).getSalaryPeriod();

        List<Employee> all = employeeRepo.findByCompanyIdOrderByFirstNameAscLastNameAsc(p.companyId());
        List<Employee> active = all.stream().filter(e -> Employee.ACTIVE.equals(e.getStatus())).toList();

        // managers see their own team; employees see the company headcount but no money
        List<Employee> scoped = active;
        if (p.role() == com.empmg.platform.security.AccessRole.MANAGER && p.employeeId() != null) {
            scoped = active.stream().filter(e -> p.employeeId().equals(e.getManagerId())).toList();
        }

        BigDecimal totalCost = null;
        List<TierCard> byTier = new ArrayList<>();
        if (seeMoney) {
            Map<Long, List<VariableComponent>> comps = new HashMap<>();
            for (VariableComponent c : componentRepo.findByCompanyId(p.companyId())) {
                comps.computeIfAbsent(c.getEmployeeId(), k -> new ArrayList<>()).add(c);
            }
            Map<Long, BigDecimal> costByTier = new LinkedHashMap<>();
            BigDecimal sum = BigDecimal.ZERO;
            for (Employee e : active) {
                var b = compensationService.monthly(e, comps.getOrDefault(e.getId(), List.of()), period);
                sum = sum.add(b.totalMonthly());
                costByTier.merge(e.getTierId(), b.totalMonthly(), BigDecimal::add);
            }
            totalCost = sum.setScale(2, RoundingMode.HALF_UP);
            tierRepo.findByCompanyIdOrderBySortOrderAscIdAsc(p.companyId()).forEach(t -> {
                long count = active.stream().filter(e -> t.getId().equals(e.getTierId())).count();
                byTier.add(new TierCard(t.getId(), t.getName(), t.getColor(), count,
                        costByTier.getOrDefault(t.getId(), BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)));
            });
            long noTier = active.stream().filter(e -> e.getTierId() == null).count();
            if (noTier > 0) {
                byTier.add(new TierCard(null, "No tier", null, noTier,
                        costByTier.getOrDefault(null, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)));
            }
        } else {
            tierRepo.findByCompanyIdOrderBySortOrderAscIdAsc(p.companyId()).forEach(t ->
                    byTier.add(new TierCard(t.getId(), t.getName(), t.getColor(),
                            active.stream().filter(e -> t.getId().equals(e.getTierId())).count(), null)));
        }

        // upcoming raises: proposed or approved, not yet applied
        List<UpcomingRaise> upcoming = new ArrayList<>();
        if (p.role().canProposeRaise()) {
            boolean seeSalary = salaryVisibility.canSeeSalaries(p);
            raiseRepo.findByCompanyIdOrderByCreatedAtDesc(p.companyId()).stream()
                    .filter(r -> com.empmg.platform.compensation.domain.Raise.PENDING.equals(r.getStatus())
                            || com.empmg.platform.compensation.domain.Raise.APPROVED.equals(r.getStatus()))
                    .filter(r -> !r.getEffectiveDate().isBefore(LocalDate.now()))
                    .sorted(Comparator.comparing(r -> r.getEffectiveDate()))
                    .limit(5)
                    .forEach(r -> upcoming.add(new UpcomingRaise(r.getId(), r.getStatus(), r.getEffectiveDate(),
                            r.getScopeNote(), -1, null)));
        }

        List<RecentChange> changes = auditRepo.findByCompanyIdOrderByCreatedAtDesc(p.companyId(),
                        PageRequest.of(0, 8)).getContent().stream()
                .map(a -> new RecentChange(a.getAction(), a.getActorEmail(), a.getCreatedAt(), a.getDetail()))
                .toList();

        return new Dashboard(scoped.size(), totalCost, byTier, upcoming, changes);
    }
}

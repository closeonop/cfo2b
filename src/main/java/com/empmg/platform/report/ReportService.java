package com.empmg.platform.report;

import com.empmg.platform.audit.service.AuditService;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.common.CsvSafety;
import com.empmg.platform.compensation.domain.SalaryHistory;
import com.empmg.platform.compensation.domain.VariableComponent;
import com.empmg.platform.compensation.repo.SalaryHistoryRepo;
import com.empmg.platform.compensation.repo.VariableComponentRepo;
import com.empmg.platform.compensation.service.CompensationService;
import com.empmg.platform.compensation.service.MoneyMath;
import com.empmg.platform.employee.domain.Employee;
import com.empmg.platform.employee.repo.EmployeeRepo;
import com.empmg.platform.employee.service.SalaryVisibility;
import com.empmg.platform.security.AuthPrincipal;
import com.empmg.platform.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Simple, useful reports, all exportable. Every table is built in memory — at a
 * few hundred employees per company this is instant and keeps one code path.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    public record Table(List<String> columns, List<List<String>> rows, List<List<String>> totals) {}

    private final EmployeeRepo employeeRepo;
    private final SalaryHistoryRepo salaryHistoryRepo;
    private final VariableComponentRepo componentRepo;
    private final CompensationService compensationService;
    private final SalaryVisibility salaryVisibility;
    private final AuditService audit;
    private final com.empmg.platform.tier.repo.TierRepo tierRepo;
    private final com.empmg.platform.employee.repo.JobRoleRepo jobRoleRepo;

    // ---------- payroll cost summary (monthly base cost for the last N months) ----------

    @Transactional(readOnly = true)
    public Table payrollSummary(AuthPrincipal p, int months) {
        salaryVisibility.assertCanSeeSalaries(p);
        Long cid = p.companyId();
        String period = salaryVisibility.company(cid).getSalaryPeriod();
        List<Employee> employees = employeeRepo.findByCompanyIdOrderByFirstNameAscLastNameAsc(cid);
        List<SalaryHistory> history = salaryHistoryRepo.findByCompanyIdOrderByCreatedAtDesc(cid);

        Map<Long, List<SalaryHistory>> byEmployee = new HashMap<>();
        for (SalaryHistory h : history) byEmployee.computeIfAbsent(h.getEmployeeId(), k -> new ArrayList<>()).add(h);
        for (List<SalaryHistory> list : byEmployee.values()) {
            list.sort(Comparator.comparing(SalaryHistory::getEffectiveDate).thenComparing(SalaryHistory::getId));
        }

        LocalDate now = LocalDate.now().withDayOfMonth(1);
        List<List<String>> rows = new ArrayList<>();
        for (int i = months - 1; i >= 0; i--) {
            LocalDate monthEnd = now.minusMonths(i).plusMonths(1).minusDays(1);
            BigDecimal total = BigDecimal.ZERO;
            for (Employee e : employees) {
                LocalDate leftOn = e.getUpdatedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
                if (!Employee.ACTIVE.equals(e.getStatus()) && leftOn.isAfter(monthEnd)) continue;
                BigDecimal salary = salaryAt(byEmployee.get(e.getId()), monthEnd);
                if (salary != null) total = total.add(MoneyMath.monthlyOf(salary, period));
            }
            rows.add(List.of(monthEnd.getYear() + "-" + String.format("%02d", monthEnd.getMonthValue()),
                    total.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        }
        audit.record(p, cid, "REPORT_VIEWED", "REPORT", "payroll-summary", Map.of("months", months));
        return new Table(List.of("month", "base_cost_" + period.toLowerCase()), rows, null);
    }

    private BigDecimal salaryAt(List<SalaryHistory> list, LocalDate date) {
        if (list == null) return null;
        SalaryHistory latest = null;
        for (SalaryHistory h : list) {
            if (!h.getEffectiveDate().isAfter(date) && (latest == null || !h.getEffectiveDate().isBefore(latest.getEffectiveDate()))) {
                latest = h;
            }
        }
        return latest != null ? latest.getNewSalary() : null;
    }

    // ---------- cost by tier / role / team ----------

    @Transactional(readOnly = true)
    public Table costBy(AuthPrincipal p, String group) {
        salaryVisibility.assertCanSeeSalaries(p);
        Long cid = p.companyId();
        String period = salaryVisibility.company(cid).getSalaryPeriod();
        List<Employee> employees = activeEmployees(cid);
        Map<Long, List<VariableComponent>> comps = componentsByEmployee(cid);

        record Acc(int headcount, BigDecimal base, BigDecimal variable) {}
        Map<String, Acc> groups = new TreeMap<>();
        for (Employee e : employees) {
            String key = switch (group) {
                case "tier" -> e.getTierId() == null ? "No tier" : tierName(cid, e.getTierId());
                case "role" -> e.getJobRoleId() == null ? "No role" : roleName(cid, e.getJobRoleId());
                case "team" -> e.getTeam() == null || e.getTeam().isBlank() ? "No team" : e.getTeam();
                default -> throw new ApiException("Group by tier, role, or team.");
            };
            var breakdown = compensationService.monthly(e,
                    comps.getOrDefault(e.getId(), List.of()), period);
            Acc acc = groups.getOrDefault(key, new Acc(0, BigDecimal.ZERO, BigDecimal.ZERO));
            groups.put(key, new Acc(acc.headcount() + 1,
                    acc.base().add(breakdown.baseMonthly()), acc.variable().add(breakdown.variableMonthly())));
        }
        List<List<String>> rows = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            rows.add(List.of(entry.getKey(),
                    String.valueOf(entry.getValue().headcount()),
                    entry.getValue().base().toPlainString(),
                    entry.getValue().variable().toPlainString(),
                    entry.getValue().base().add(entry.getValue().variable()).toPlainString()));
        }
        audit.record(p, cid, "REPORT_VIEWED", "REPORT", "cost-by-" + group, Map.of());
        return new Table(List.of(group, "headcount", "base_monthly", "variable_monthly", "total_monthly"),
                rows, null);
    }

    // ---------- salary history report ----------

    @Transactional(readOnly = true)
    public Table salaryHistory(AuthPrincipal p, LocalDate from, LocalDate to) {
        salaryVisibility.assertCanSeeSalaries(p);
        Long cid = p.companyId();
        Map<Long, String> names = employeeNames(cid);
        List<SalaryHistory> history = salaryHistoryRepo.findByCompanyIdOrderByCreatedAtDesc(cid).stream()
                .filter(h -> (from == null || !h.getEffectiveDate().isBefore(from))
                        && (to == null || !h.getEffectiveDate().isAfter(to)))
                .toList();
        List<List<String>> rows = history.stream()
                .map(h -> List.of(
                        CsvSafety.safe(names.getOrDefault(h.getEmployeeId(), "#" + h.getEmployeeId())),
                        h.getEffectiveDate().toString(),
                        h.getOldSalary().toPlainString(),
                        h.getNewSalary().toPlainString(),
                        h.getPercent() == null ? "" : h.getPercent().toPlainString(),
                        CsvSafety.safe(h.getReason()),
                        h.getReversalOfId() != null ? "reversal" : ""))
                .toList();
        audit.record(p, cid, "REPORT_VIEWED", "REPORT", "salary-history", Map.of());
        return new Table(List.of("employee", "effective_date", "old_salary", "new_salary", "percent", "reason", "type"),
                rows, null);
    }

    // ---------- variable pay summary ----------

    @Transactional(readOnly = true)
    public Table variablePay(AuthPrincipal p) {
        salaryVisibility.assertCanSeeSalaries(p);
        Long cid = p.companyId();
        String period = salaryVisibility.company(cid).getSalaryPeriod();
        Map<Long, String> names = employeeNames(cid);
        Map<Long, List<VariableComponent>> comps = componentsByEmployee(cid);

        List<List<String>> rows = new ArrayList<>();
        for (Employee e : activeEmployees(cid)) {
            for (VariableComponent c : comps.getOrDefault(e.getId(), List.of())) {
                if (!c.isActive()) continue;
                String monthly;
                if (VariableComponent.PERCENT.equals(c.getCalcType())) {
                    monthly = MoneyMath.monthlyOf(e.getBaseSalary(), period)
                            .multiply(c.getValue()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                            .toPlainString();
                } else if (VariableComponent.ONE_TIME.equals(c.getPeriod())) {
                    monthly = "one-time";
                } else {
                    monthly = c.monthlyEquivalent().toPlainString();
                }
                rows.add(List.of(
                        CsvSafety.safe(names.getOrDefault(e.getId(), "#" + e.getId())),
                        CsvSafety.safe(c.getName()), c.getCalcType(), c.getValue().toPlainString(),
                        c.getPeriod(), monthly));
            }
        }
        audit.record(p, cid, "REPORT_VIEWED", "REPORT", "variable-pay", Map.of());
        return new Table(List.of("employee", "component", "calc_type", "value", "period", "monthly_equivalent"),
                rows, null);
    }

    // ---------- helpers ----------

    private List<Employee> activeEmployees(Long cid) {
        return employeeRepo.findByCompanyIdOrderByFirstNameAscLastNameAsc(cid).stream()
                .filter(e -> Employee.ACTIVE.equals(e.getStatus()))
                .toList();
    }

    private Map<Long, String> employeeNames(Long cid) {
        Map<Long, String> names = new HashMap<>();
        for (Employee e : employeeRepo.findByCompanyIdOrderByFirstNameAscLastNameAsc(cid)) {
            names.put(e.getId(), e.getFirstName() + " " + e.getLastName());
        }
        return names;
    }

    private Map<Long, List<VariableComponent>> componentsByEmployee(Long cid) {
        Map<Long, List<VariableComponent>> out = new HashMap<>();
        for (VariableComponent c : componentRepo.findByCompanyId(cid)) {
            out.computeIfAbsent(c.getEmployeeId(), k -> new ArrayList<>()).add(c);
        }
        return out;
    }

    private String tierName(Long cid, Long id) {
        return tierRepo.findByIdAndCompanyId(id, cid).map(t -> t.getName()).orElse("Unknown tier");
    }

    private String roleName(Long cid, Long id) {
        return jobRoleRepo.findByIdAndCompanyId(id, cid).map(r -> r.getName()).orElse("Unknown role");
    }
}

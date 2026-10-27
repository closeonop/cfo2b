package com.empmg.platform.compensation.service;

import com.empmg.platform.audit.service.AuditService;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.compensation.domain.Raise;
import com.empmg.platform.compensation.domain.RaiseItem;
import com.empmg.platform.compensation.domain.SalaryHistory;
import com.empmg.platform.compensation.repo.RaiseItemRepo;
import com.empmg.platform.compensation.repo.RaiseRepo;
import com.empmg.platform.compensation.repo.SalaryHistoryRepo;
import com.empmg.platform.employee.domain.Employee;
import com.empmg.platform.employee.repo.EmployeeRepo;
import com.empmg.platform.employee.service.SalaryVisibility;
import com.empmg.platform.security.AccessRole;
import com.empmg.platform.security.AuthPrincipal;
import com.empmg.platform.tier.domain.Tier;
import com.empmg.platform.tier.repo.TierRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Raises: preview → confirm → (approve) → apply. Salary history is append-only.
 * Future-dated raises are applied by RaiseScheduler on their effective date.
 */
@Service
@RequiredArgsConstructor
public class RaiseService {

    private final EmployeeRepo employeeRepo;
    private final TierRepo tierRepo;
    private final RaiseRepo raiseRepo;
    private final RaiseItemRepo raiseItemRepo;
    private final SalaryHistoryRepo salaryHistoryRepo;
    private final SalaryVisibility salaryVisibility;
    private final AuditService audit;

    // ---------- request / response ----------

    public record RaiseRequest(List<Long> employeeIds, Long tierId, BigDecimal percent, BigDecimal fixedAmount,
                               LocalDate effectiveDate, String reason, String capPolicy, Long targetTierId) {}

    public record PreviewItem(Long employeeId, String name, String tier, BigDecimal oldSalary,
                              BigDecimal newSalary, BigDecimal percentApplied, boolean capped,
                              String tierWarning, Boolean tierChanged) {}

    public record Preview(List<PreviewItem> items, int affected,
                          BigDecimal totalOldMonthly, BigDecimal totalNewMonthly,
                          BigDecimal monthlyDelta, BigDecimal annualDelta, List<String> warnings) {}

    public record RaiseSummary(Long id, String status, BigDecimal percent, BigDecimal fixedAmount,
                               LocalDate effectiveDate, String reason, String scopeNote, int itemCount,
                               BigDecimal totalOldMonthly, BigDecimal totalNewMonthly, BigDecimal monthlyDelta,
                               String createdBy, Instant createdAt, int cappedCount) {}

    // ---------- preview ----------

    @Transactional(readOnly = true)
    public Preview preview(AuthPrincipal p, RaiseRequest req) {
        if (!p.role().canProposeRaise()) {
            throw new ApiException("You don't have permission to set up raises.", 403);
        }
        validateRequest(req);
        CompanyData data = loadScope(p, req, req.capPolicy() == null ? "ALLOW" : req.capPolicy(), req.targetTierId());
        return buildPreview(p, data, req.effectiveDate());
    }

    // ---------- create / approve / reject / cancel ----------

    @Transactional
    public RaiseSummary create(AuthPrincipal p, RaiseRequest req) {
        if (!p.role().canProposeRaise()) {
            throw new ApiException("You don't have permission to set up raises.", 403);
        }
        validateRequest(req);
        String capPolicy = req.capPolicy() == null ? "ALLOW" : req.capPolicy();
        CompanyData data = loadScope(p, req, capPolicy, req.targetTierId());
        if (data.items().isEmpty()) {
            throw new ApiException("No employees were selected.");
        }

        Raise raise = Raise.builder()
                .companyId(p.companyId())
                .status(p.role().canApproveRaise() ? Raise.APPROVED : Raise.PENDING)
                .percent(req.percent())
                .fixedAmount(req.fixedAmount())
                .effectiveDate(req.effectiveDate())
                .reason(req.reason().trim())
                .scopeNote(data.scopeNote())
                .createdByUserId(p.userId())
                .cappedCount((int) data.items().stream().filter(Item::capped).count())
                .createdAt(Instant.now())
                .build();
        raise = raiseRepo.save(raise);

        for (Item item : data.items()) {
            raiseItemRepo.save(RaiseItem.builder()
                    .raiseId(raise.getId())
                    .employeeId(item.employee().getId())
                    .oldSalary(item.employee().getBaseSalary())
                    .newSalary(item.newSalary())
                    .capped(item.capped())
                    .build());
        }

        audit.record(p, p.companyId(), p.role().canApproveRaise() ? "RAISE_CREATED_APPROVED" : "RAISE_PROPOSED",
                "RAISE", raise.getId(), Map.of("affected", data.items().size(),
                        "effectiveDate", req.effectiveDate().toString()));

        if (Raise.APPROVED.equals(raise.getStatus())) {
            applyIfDue(raise, p);
        }
        return summarize(raise, salaryVisibility.canSeeSalaries(p));
    }

    @Transactional
    public RaiseSummary approve(AuthPrincipal p, Long id) {
        if (!p.role().canApproveRaise()) {
            throw new ApiException("Only Admin or Finance can approve raises.", 403);
        }
        Raise raise = load(p, id);
        if (!Raise.PENDING.equals(raise.getStatus())) {
            throw new ApiException("Only proposed raises can be approved.");
        }
        raise.setStatus(Raise.APPROVED);
        raise.setApprovedByUserId(p.userId());
        raise.setApprovedAt(Instant.now());
        raiseRepo.save(raise);
        audit.record(p, p.companyId(), "RAISE_APPROVED", "RAISE", id, Map.of());
        applyIfDue(raise, p);
        return summarize(raise, salaryVisibility.canSeeSalaries(p));
    }

    @Transactional
    public RaiseSummary reject(AuthPrincipal p, Long id) {
        if (!p.role().canApproveRaise()) {
            throw new ApiException("Only Admin or Finance can reject raises.", 403);
        }
        Raise raise = load(p, id);
        if (!Raise.PENDING.equals(raise.getStatus())) {
            throw new ApiException("Only proposed raises can be rejected.");
        }
        raise.setStatus(Raise.REJECTED);
        raiseRepo.save(raise);
        audit.record(p, p.companyId(), "RAISE_REJECTED", "RAISE", id, Map.of());
        return summarize(raise, salaryVisibility.canSeeSalaries(p));
    }

    @Transactional
    public RaiseSummary cancel(AuthPrincipal p, Long id) {
        Raise raise = load(p, id);
        boolean isCreator = raise.getCreatedByUserId().equals(p.userId());
        if (!isCreator && !p.role().canApproveRaise()) {
            throw new ApiException("You can only cancel raises you created.", 403);
        }
        if (!Raise.PENDING.equals(raise.getStatus()) && !Raise.APPROVED.equals(raise.getStatus())) {
            throw new ApiException("A raise that has already been applied can't be cancelled — record a reversing entry instead.");
        }
        raise.setStatus(Raise.CANCELLED);
        raiseRepo.save(raise);
        audit.record(p, p.companyId(), "RAISE_CANCELLED", "RAISE", id, Map.of());
        return summarize(raise, salaryVisibility.canSeeSalaries(p));
    }

    @Transactional(readOnly = true)
    public List<RaiseSummary> list(AuthPrincipal p, List<String> statuses) {
        if (!p.role().canProposeRaise()) {
            throw new ApiException("You don't have permission to view raises.", 403);
        }
        boolean seeSalary = salaryVisibility.canSeeSalaries(p);
        List<Raise> raises = statuses == null || statuses.isEmpty()
                ? raiseRepo.findByCompanyIdOrderByCreatedAtDesc(p.companyId())
                : raiseRepo.findByCompanyIdAndStatusInOrderByCreatedAtDesc(p.companyId(), statuses);
        return raises.stream().map(r -> summarize(r, seeSalary)).toList();
    }

    @Transactional(readOnly = true)
    public RaiseDetail detail(AuthPrincipal p, Long id) {
        if (!p.role().canProposeRaise()) throw new ApiException("You do not have permission to view raises.", 403);
        Raise raise = load(p, id);
        boolean seeSalary = salaryVisibility.canSeeSalaries(p);
        List<RaiseItem> items = raiseItemRepo.findByRaiseId(raise.getId());
        Map<Long, String> names = new HashMap<>();
        employeeRepo.findByCompanyIdOrderByFirstNameAscLastNameAsc(p.companyId())
                .forEach(e -> names.put(e.getId(), e.getFirstName() + " " + e.getLastName()));
        BigDecimal totalOld = BigDecimal.ZERO, totalNew = BigDecimal.ZERO;
        List<DetailItem> rows = new ArrayList<>();
        for (RaiseItem item : items) {
            totalOld = totalOld.add(item.getOldSalary());
            totalNew = totalNew.add(item.getNewSalary());
            rows.add(new DetailItem(item.getEmployeeId(), names.get(item.getEmployeeId()),
                    seeSalary ? item.getOldSalary() : null, seeSalary ? item.getNewSalary() : null, item.isCapped()));
        }
        return new RaiseDetail(summarize(raise, seeSalary), rows,
                seeSalary ? summarize(raise).monthlyDelta() : null);
    }

    public record DetailItem(Long employeeId, String name, BigDecimal oldSalary, BigDecimal newSalary, boolean capped) {}
    public record RaiseDetail(RaiseSummary raise, List<DetailItem> items, BigDecimal monthlyDelta) {}

    // ---------- applying (also used by the scheduler) ----------

    /** Applies an approved raise whose effective date has arrived. */
    @Transactional
    public void applyIfDue(Raise raise, AuthPrincipal actor) {
        if (!Raise.APPROVED.equals(raise.getStatus())) return;
        if (raise.getEffectiveDate().isAfter(LocalDate.now())) return;

        List<RaiseItem> items = raiseItemRepo.findByRaiseId(raise.getId());
        for (RaiseItem item : items) {
            Employee e = employeeRepo.findByIdAndCompanyId(item.getEmployeeId(), raise.getCompanyId()).orElse(null);
            if (e == null) continue; // employee removed since preview
            e.setBaseSalary(item.getNewSalary());
            e.setUpdatedAt(Instant.now());
            employeeRepo.save(e);
            salaryHistoryRepo.save(SalaryHistory.builder()
                    .companyId(raise.getCompanyId())
                    .employeeId(e.getId())
                    .effectiveDate(raise.getEffectiveDate())
                    .oldSalary(item.getOldSalary())
                    .newSalary(item.getNewSalary())
                    .percent(raise.getPercent())
                    .reason(raise.getReason())
                    .changedByUserId(actor != null ? actor.userId() : raise.getApprovedByUserId())
                    .raiseId(raise.getId())
                    .createdAt(Instant.now())
                    .build());
        }
        raise.setStatus(Raise.APPLIED);
        raise.setAppliedAt(Instant.now());
        raiseRepo.save(raise);
        audit.record(actor, raise.getCompanyId(), "RAISE_APPLIED", "RAISE", raise.getId(),
                Map.of("people", items.size(), "effectiveDate", raise.getEffectiveDate().toString()));
    }

    // ---------- reversal ----------

    @Transactional
    public Map<String, Object> reverseHistoryEntry(AuthPrincipal p, Long historyId, String reason) {
        salaryVisibility.assertCanChangeSalaries(p);
        SalaryHistory entry = salaryHistoryRepo.findById(historyId)
                .filter(h -> h.getCompanyId().equals(p.companyId()))
                .orElseThrow(() -> new ApiException("That salary history entry doesn't exist.", 404));
        if (entry.getOldSalary().signum() == 0) {
            throw new ApiException("That entry is the employee's initial salary — set a new salary instead of reversing it.");
        }
        Employee e = employeeRepo.findByIdAndCompanyId(entry.getEmployeeId(), p.companyId())
                .orElseThrow(() -> new ApiException("Employee not found.", 404));
        BigDecimal current = e.getBaseSalary();
        BigDecimal restored = entry.getOldSalary();
        e.setBaseSalary(restored);
        e.setUpdatedAt(Instant.now());
        employeeRepo.save(e);
        SalaryHistory reversal = salaryHistoryRepo.save(SalaryHistory.builder()
                .companyId(p.companyId())
                .employeeId(e.getId())
                .effectiveDate(LocalDate.now())
                .oldSalary(current)
                .newSalary(restored)
                .percent(current.signum() == 0 ? null : restored.subtract(current)
                        .divide(current, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)))
                .reason(reason == null || reason.isBlank()
                        ? "Reversal of " + entry.getEffectiveDate() + " change" : reason.trim())
                .changedByUserId(p.userId())
                .reversalOfId(entry.getId())
                .createdAt(Instant.now())
                .build());
        audit.record(p, p.companyId(), "SALARY_REVERSED", "SALARY_HISTORY", reversal.getId(),
                Map.of("employeeId", e.getId(), "of", entry.getId()));
        return Map.of("id", reversal.getId(), "message",
                "Salary reversed. A new entry was added to the history — nothing was deleted.");
    }

    // ---------- internals ----------

    private Raise load(AuthPrincipal p, Long id) {
        return raiseRepo.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> new ApiException("Raise not found.", 404));
    }

    private void validateRequest(RaiseRequest req) {
        if (req.effectiveDate() == null) throw new ApiException("Please choose an effective date.");
        if (req.reason() == null || req.reason().isBlank()) throw new ApiException("Please give a reason for the raise.");
        if ((req.percent() == null) == (req.fixedAmount() == null)) {
            throw new ApiException("Enter exactly one of percent or fixed amount.");
        }
        if (req.percent() != null && (req.percent().signum() < 0 || req.percent().compareTo(new BigDecimal("1000")) > 0)) {
            throw new ApiException("Percent must be between 0 and 1000.");
        }
        if (req.fixedAmount() != null && req.fixedAmount().signum() < 0) {
            throw new ApiException("The fixed amount can't be negative.");
        }
        String cp = req.capPolicy() == null ? "ALLOW" : req.capPolicy();
        if (!List.of("CAP", "ALLOW").contains(cp)) {
            throw new ApiException("Choose CAP or ALLOW for the tier maximum option.");
        }
    }

    private record Item(Employee employee, BigDecimal newSalary, boolean capped, BigDecimal percentApplied, String tierWarning, Boolean tierChanged) {}

    private record CompanyData(List<Item> items, String scopeNote) {}

    private CompanyData loadScope(AuthPrincipal p, RaiseRequest req, String capPolicy, Long targetTierId) {
        List<Employee> employees = new ArrayList<>();
        String scopeNote;
        if (req.employeeIds() != null && !req.employeeIds().isEmpty() && (req.tierId() != null || req.employeeIds().contains(null)
                || new HashSet<>(req.employeeIds()).size() != req.employeeIds().size()))
            throw new ApiException("Select a tier or unique employees, not both.");
        if (req.employeeIds() != null && !req.employeeIds().isEmpty()) {
            for (Long id : req.employeeIds()) {
                employees.add(employeeRepo.findByIdAndCompanyId(id, p.companyId())
                        .orElseThrow(() -> new ApiException("One of the selected employees no longer exists.")));
            }
            scopeNote = employees.size() + (employees.size() == 1 ? " person" : " people") + " selected";
        } else if (req.tierId() != null) {
            Tier tier = tierRepo.findByIdAndCompanyId(req.tierId(), p.companyId())
                    .orElseThrow(() -> new ApiException("That tier doesn't exist."));
            employees = employeeRepo.findByCompanyIdAndTierId(p.companyId(), tier.getId()).stream()
                    .filter(e -> Employee.ACTIVE.equals(e.getStatus()))
                    .toList();
            scopeNote = "Tier " + tier.getName() + " — " + employees.size()
                    + (employees.size() == 1 ? " person" : " people");
        } else {
            throw new ApiException("Select employees or a tier for the raise.");
        }

        List<Item> items = new ArrayList<>();
        for (Employee e : employees) {
            if (!Employee.ACTIVE.equals(e.getStatus())) throw new ApiException("Raises require active employees.");
            Tier tier = e.getTierId() != null ? tierRepo.findByIdAndCompanyId(e.getTierId(), p.companyId()).orElse(null) : null;
            BigDecimal tierMax = tier != null ? tier.getMaxSalary() : null;
            MoneyMath.Outcome outcome = MoneyMath.computeRaise(e.getBaseSalary(), req.percent(), req.fixedAmount(),
                    tierMax, capPolicy, salaryVisibility.company(p.companyId()).getRoundingRule());
            Boolean tierChanged = null;
            String warning = null;
            if (outcome.capped()) {
                warning = e.getBaseSalary().compareTo(tierMax) > 0
                        ? "Already above the band maximum. Existing pay is preserved."
                        : "Capped at the band maximum.";
            } else if (tierMax != null && outcome.newSalary().compareTo(tierMax) > 0) {
                warning = "Goes above the tier maximum (allowed).";
            }
            items.add(new Item(e, outcome.newSalary(), outcome.capped(), outcome.percentApplied(), warning, tierChanged));
        }
        return new CompanyData(items, scopeNote);
    }

    private Preview buildPreview(AuthPrincipal p, CompanyData data, LocalDate effectiveDate) {
        String period = salaryVisibility.company(p.companyId()).getSalaryPeriod();
        BigDecimal totalOld = BigDecimal.ZERO, totalNew = BigDecimal.ZERO;
        boolean seeSalary = salaryVisibility.canSeeSalaries(p);
        List<PreviewItem> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int cappedTotal = 0;
        for (Item item : data.items()) {
            BigDecimal oldMonthly = MoneyMath.monthlyOf(item.employee().getBaseSalary(), period);
            BigDecimal newMonthly = MoneyMath.monthlyOf(item.newSalary(), period);
            totalOld = totalOld.add(oldMonthly);
            totalNew = totalNew.add(newMonthly);
            if (item.capped()) cappedTotal++;
            Tier tier = item.employee().getTierId() != null
                    ? tierRepo.findByIdAndCompanyId(item.employee().getTierId(), p.companyId()).orElse(null) : null;
            rows.add(new PreviewItem(item.employee().getId(),
                    item.employee().getFirstName() + " " + item.employee().getLastName(),
                    tier != null ? tier.getName() : null,
                    seeSalary ? item.employee().getBaseSalary() : null, seeSalary ? item.newSalary() : null, seeSalary ? item.percentApplied() : null,
                    item.capped(), item.tierWarning(), item.tierChanged()));
        }
        if (cappedTotal > 0) {
            warnings.add(cappedTotal + (cappedTotal == 1 ? " person" : " people")
                    + " limited by the band policy. Existing pay will not be reduced.");
        }
        if (effectiveDate.isAfter(LocalDate.now())) {
            warnings.add("This raise is scheduled — it applies automatically on "
                    + effectiveDate + ".");
        }
        BigDecimal monthlyDelta = totalNew.subtract(totalOld).setScale(2, RoundingMode.HALF_UP);
        return new Preview(rows, data.items().size(), seeSalary ? totalOld.setScale(2, RoundingMode.HALF_UP) : null,
                seeSalary ? totalNew.setScale(2, RoundingMode.HALF_UP) : null, seeSalary ? monthlyDelta : null,
                seeSalary ? monthlyDelta.multiply(BigDecimal.valueOf(12)).setScale(2, RoundingMode.HALF_UP) : null, warnings);
    }

    private RaiseSummary summarize(Raise raise) {
        return summarize(raise, true);
    }

    private RaiseSummary summarize(Raise raise, boolean seeSalary) {
        List<RaiseItem> items = raiseItemRepo.findByRaiseId(raise.getId());
        BigDecimal totalOld = BigDecimal.ZERO, totalNew = BigDecimal.ZERO;
        String period = salaryVisibility.company(raise.getCompanyId()).getSalaryPeriod();
        for (RaiseItem item : items) {
            totalOld = totalOld.add(MoneyMath.monthlyOf(item.getOldSalary(), period));
            totalNew = totalNew.add(MoneyMath.monthlyOf(item.getNewSalary(), period));
        }
        return new RaiseSummary(raise.getId(), raise.getStatus(), raise.getPercent(), raise.getFixedAmount(),
                raise.getEffectiveDate(), raise.getReason(), raise.getScopeNote(), items.size(),
                seeSalary ? totalOld : null, seeSalary ? totalNew : null,
                seeSalary ? totalNew.subtract(totalOld) : null, null, raise.getCreatedAt(), raise.getCappedCount());
    }
}

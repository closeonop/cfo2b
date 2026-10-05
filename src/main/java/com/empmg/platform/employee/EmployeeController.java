package com.empmg.platform.employee;

import com.empmg.platform.common.ApiException;
import com.empmg.platform.compensation.repo.SalaryHistoryRepo;
import com.empmg.platform.compensation.repo.VariableComponentRepo;
import com.empmg.platform.compensation.service.CompensationService;
import com.empmg.platform.employee.domain.Employee;
import com.empmg.platform.employee.repo.EmployeeRepo;
import com.empmg.platform.employee.service.CsvService;
import com.empmg.platform.employee.service.EmployeeService;
import com.empmg.platform.employee.service.SalaryVisibility;
import com.empmg.platform.security.AuthPrincipal;
import com.empmg.platform.security.SecurityUtils;
import com.empmg.platform.user.repo.UserRepo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;
    private final CsvService csvService;
    private final SalaryVisibility salaryVisibility;
    private final EmployeeRepo employeeRepo;
    private final VariableComponentRepo componentRepo;
    private final SalaryHistoryRepo salaryHistoryRepo;
    private final CompensationService compensationService;
    private final UserRepo userRepo;
    private final com.empmg.platform.audit.service.AuditService audit;

    public record UpsertRequest(
            String employeeCode, @NotBlank String firstName, @NotBlank String lastName,
            String email, String phone, Long jobRoleId, Long tierId, Long managerId, String team,
            LocalDate startDate, String status, BigDecimal salary, String salaryReason) {

        EmployeeService.EmployeeInput toInput() {
            return new EmployeeService.EmployeeInput(employeeCode, firstName, lastName, email, phone,
                    jobRoleId, tierId, managerId, team, startDate, status);
        }
    }

    @GetMapping
    public List<EmployeeDto> list() {
        return employeeService.list(SecurityUtils.current());
    }

    @GetMapping("/teams")
    public List<String> teams() {
        return employeeService.teams(SecurityUtils.current());
    }

    @GetMapping("/{id}")
    public EmployeeDto get(@PathVariable Long id) {
        return employeeService.get(SecurityUtils.current(), id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','CFO','HR')")
    public EmployeeDto create(@Valid @RequestBody UpsertRequest req) {
        AuthPrincipal p = SecurityUtils.current();
        if (req.salary() != null && (req.salaryReason() == null || req.salaryReason().isBlank())) {
            req = new UpsertRequest(req.employeeCode(), req.firstName(), req.lastName(), req.email(), req.phone(),
                    req.jobRoleId(), req.tierId(), req.managerId(), req.team(), req.startDate(), req.status(),
                    req.salary(), "Initial salary");
        }
        return employeeService.create(p, req.toInput(), req.salary(), req.salaryReason());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CFO','HR')")
    public EmployeeDto update(@PathVariable Long id, @Valid @RequestBody UpsertRequest req) {
        return employeeService.update(SecurityUtils.current(), id, req.toInput(), req.salary(), req.salaryReason());
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','CFO','HR')")
    public EmployeeDto setStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return employeeService.setStatus(SecurityUtils.current(), id, body.get("status"));
    }

    // ---------- compensation (per employee) ----------

    public record CompensationView(BigDecimal baseSalary, String salaryPeriod, List<ComponentView> components,
                                   BigDecimal variableMonthly, BigDecimal oneTimeTotal, BigDecimal totalMonthly,
                                   List<HistoryView> history, String tierWarning) {}

    public record ComponentView(Long id, String name, String calcType, BigDecimal value, String period,
                                LocalDate startDate, LocalDate endDate, boolean active) {}

    public record HistoryView(Long id, LocalDate effectiveDate, BigDecimal oldSalary, BigDecimal newSalary,
                              BigDecimal percent, String reason, String changedBy, boolean isReversal, Long reversalOfId) {}

    @GetMapping("/{id}/compensation")
    public CompensationView compensation(@PathVariable Long id) {
        AuthPrincipal p = SecurityUtils.current();
        com.empmg.platform.company.domain.Company company = salaryVisibility.company(p.companyId());
        var employeeEntity = employeeRepo.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> new ApiException("Employee not found.", 404));
        var employee = employeeService.get(p, id);

        boolean self = p.employeeId() != null && p.employeeId().equals(id);
        boolean maySee = self || salaryVisibility.canSeeSalaries(p);

        var components = componentRepo.findByCompanyIdAndEmployeeIdOrderByIdAsc(p.companyId(), id);
        var breakdown = compensationService.monthly(
                employeeRepo.findByIdAndCompanyId(id, p.companyId()).orElseThrow(), components, company.getSalaryPeriod());
        var history = salaryHistoryRepo
                .findByCompanyIdAndEmployeeIdOrderByEffectiveDateDescIdDesc(p.companyId(), id);
        Map<Long, String> users = new java.util.HashMap<>();
        userRepo.findAllById(history.stream().map(h -> h.getChangedByUserId()).filter(java.util.Objects::nonNull).toList())
                .forEach(u -> users.put(u.getId(), u.getEmail()));

        audit.record(p, p.companyId(), "COMPENSATION_VIEWED", "EMPLOYEE", id, Map.of());
        return new CompensationView(
                maySee ? employeeEntity.getBaseSalary() : null, company.getSalaryPeriod(),
                components.stream().map(c -> new ComponentView(c.getId(), c.getName(), c.getCalcType(),
                        c.getValue(), c.getPeriod(), c.getStartDate(), c.getEndDate(), c.isActive())).toList(),
                breakdown.variableMonthly(), breakdown.oneTimeTotal(), breakdown.totalMonthly(),
                history.stream().map(h -> new HistoryView(h.getId(), h.getEffectiveDate(), h.getOldSalary(),
                        h.getNewSalary(), h.getPercent(), h.getReason(),
                        h.getChangedByUserId() != null ? users.get(h.getChangedByUserId()) : null,
                        h.getReversalOfId() != null, h.getReversalOfId())).toList(),
                employee.salaryOutsideTier());
    }

    // ---------- CSV import / export ----------

    @GetMapping("/import/template")
    public ResponseEntity<byte[]> template() {
        return csvFile(csvService.template().getBytes(StandardCharsets.UTF_8), "employee-import-template.csv");
    }

    @PostMapping("/import/preview")
    @PreAuthorize("hasAnyRole('ADMIN','CFO','HR')")
    public CsvService.ImportPreview importPreview(@RequestParam("file") MultipartFile file,
                                                  @RequestParam(required = false) String mapping,
                                                  @RequestParam(defaultValue = "SKIP") String duplicateMode) {
        AuthPrincipal p = SecurityUtils.current();
        return csvService.preview(file, parseMapping(mapping), duplicateMode, p);
    }

    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('ADMIN','CFO','HR')")
    public CsvService.ImportResult importApply(@RequestParam("file") MultipartFile file,
                                               @RequestParam(required = false) String mapping,
                                               @RequestParam(defaultValue = "SKIP") String duplicateMode) {
        return csvService.apply(file, parseMapping(mapping), duplicateMode, SecurityUtils.current());
    }

    @PostMapping("/import/{batchId}/undo")
    @PreAuthorize("hasAnyRole('ADMIN','CFO','HR')")
    public Map<String, String> undoImport(@PathVariable Long batchId) {
        csvService.undo(batchId, SecurityUtils.current());
        return Map.of("message", "Import undone. Your employee list is back to how it was.");
    }

    @GetMapping("/import/batches")
    @PreAuthorize("hasAnyRole('ADMIN','CFO','HR')")
    public List<CsvService.BatchInfo> batches() {
        return csvService.recentBatches(SecurityUtils.current());
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        AuthPrincipal p = SecurityUtils.current();
        boolean includeSalary = salaryVisibility.canSeeSalaries(p);
        byte[] csv = csvService.export(p, includeSalary);
        return csvFile(csv, "employees.csv");
    }

    // ---------- helpers ----------

    private static ResponseEntity<byte[]> csvFile(byte[] bytes, String name) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    private static Map<String, String> parseMapping(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new ApiException("The column mapping could not be read. Please re-map the columns.");
        }
    }
}

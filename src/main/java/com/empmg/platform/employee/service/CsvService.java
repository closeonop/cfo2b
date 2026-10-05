package com.empmg.platform.employee.service;

import com.empmg.platform.audit.service.AuditService;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.common.CsvSafety;
import com.empmg.platform.employee.domain.Employee;
import com.empmg.platform.employee.domain.ImportBatch;
import com.empmg.platform.employee.domain.JobRole;
import com.empmg.platform.employee.repo.EmployeeRepo;
import com.empmg.platform.employee.repo.ImportBatchRepo;
import com.empmg.platform.employee.repo.JobRoleRepo;
import com.empmg.platform.security.AuthPrincipal;
import com.empmg.platform.tier.domain.Tier;
import com.empmg.platform.tier.repo.TierRepo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * CSV import/export for employees. The whole import is one transaction and can
 * be undone from the stored before-snapshot.
 */
@Service
@RequiredArgsConstructor
public class CsvService {

    public static final List<String> FIELDS = List.of(
            "first_name", "last_name", "email", "phone", "employee_code",
            "job_role", "team", "manager_email", "start_date", "status", "tier", "salary");

    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("first_name", List.of("firstname", "first", "givenname")),
            Map.entry("last_name", List.of("lastname", "last", "surname", "familyname")),
            Map.entry("email", List.of("mail", "emailaddress", "workemail", "companyemail")),
            Map.entry("phone", List.of("phonenum", "phonenumber", "mobile", "telephone", "contact")),
            Map.entry("employee_code", List.of("employeecode", "employeeid", "code", "staffid")),
            Map.entry("job_role", List.of("jobrole", "role", "jobtitle", "title", "position", "designation")),
            Map.entry("team", List.of("department", "dept", "unit")),
            Map.entry("manager_email", List.of("manageremail", "manager", "managersemail", "reportsto")),
            Map.entry("start_date", List.of("startdate", "joindate", "joiningdate", "hiredate", "dateofjoining", "doj")),
            Map.entry("status", List.of("employmentstatus", "state")),
            Map.entry("tier", List.of("tiername", "grade", "band")),
            Map.entry("salary", List.of("basesalary", "basemonthlysalary", "basic", "pay", "salaryamount", "ctc")));

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EmployeeRepo employeeRepo;
    private final JobRoleRepo jobRoleRepo;
    private final TierRepo tierRepo;
    private final ImportBatchRepo batchRepo;
    private final SalaryVisibility salaryVisibility;
    private final com.empmg.platform.compensation.repo.SalaryHistoryRepo salaryHistoryRepo;
    private final com.empmg.platform.compensation.repo.VariableComponentRepo componentRepo;
    private final AuditService audit;

    // ---------- template ----------

    public String template() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", FIELDS)).append("\r\n");
        sb.append("Asha,Verma,asha@example.com,555-0100,E-001,Sales Manager,Sales,,2025-01-15,ACTIVE,Tier 2,4500\r\n");
        sb.append("Ben,Okafor,ben@example.com,,E-002,Software Engineer,Engineering,asha@example.com,2025-03-01,ACTIVE,Tier 2,5200\r\n");
        return sb.toString();
    }

    // ---------- import ----------

    public record RowResult(int row, Map<String, String> data, String action,
                            List<String> errors, List<String> warnings) {}

    public record ImportPreview(List<String> columns, Map<String, String> mapping, List<RowResult> rows,
                                int create, int update, int skip, int error) {}

    public record ImportResult(Long batchId, int created, int updated, int skipped, int errors) {}

    @Transactional(readOnly = true)
    public ImportPreview preview(MultipartFile file, Map<String, String> mapping, String duplicateMode,
                                 AuthPrincipal p) {
        ParsedCsv csv = parse(file);
        Map<String, String> m = mapping != null && !mapping.isEmpty() ? mapping : autoMap(csv.headers());
        List<RowResult> rows = validateAll(csv, m, duplicateMode, p.companyId());
        int create = 0, update = 0, skip = 0, err = 0;
        for (RowResult r : rows) {
            switch (r.action()) {
                case "CREATE" -> create++;
                case "UPDATE" -> update++;
                case "SKIP" -> skip++;
                default -> err++;
            }
        }
        return new ImportPreview(csv.headers(), m, rows, create, update, skip, err);
    }

    @Transactional
    public ImportResult apply(MultipartFile file, Map<String, String> mapping, String duplicateMode,
                              AuthPrincipal p) {
        salaryVisibility.assertCanChangeSalaries(p);
        if (!"SKIP".equals(duplicateMode) && !"UPDATE".equals(duplicateMode)) {
            throw new ApiException("Duplicate handling must be Skip or Update.");
        }
        ParsedCsv csv = parse(file);
        Map<String, String> m = mapping != null && !mapping.isEmpty() ? mapping : autoMap(csv.headers());
        List<RowResult> rows = validateAll(csv, m, duplicateMode, p.companyId());
        if (rows.stream().anyMatch(r -> "ERROR".equals(r.action()))) {
            throw new ApiException("Some rows have errors. Fix them first — nothing was changed.");
        }

        int created = 0, updated = 0, skipped = 0;
        List<Map<String, Object>> undo = new ArrayList<>();

        for (RowResult r : rows) {
            Map<String, String> d = r.data();
            String email = value(d, "email");
            Employee target = email != null
                    ? employeeRepo.findByCompanyIdAndEmailIgnoreCase(p.companyId(), email).orElse(null)
                    : null;
            if (target == null) {
                String code = value(d, "employee_code");
                if (code != null) {
                    target = employeeRepo.findByCompanyIdAndEmployeeCodeIgnoreCase(p.companyId(), code).orElse(null);
                }
            }

            if (target == null) {
                Employee e = new Employee();
                e.setCompanyId(p.companyId());
                e.setBaseSalary(parseMoney(value(d, "salary")));
                e.setStatus(Employee.ACTIVE);
                e.setCreatedAt(Instant.now());
                applyFields(e, d, p);
                e.setUpdatedAt(Instant.now());
                e = employeeRepo.save(e);
                undo.add(Map.of("action", "CREATED", "id", e.getId()));
                created++;
            } else if ("UPDATE".equals(duplicateMode)) {
                undo.add(beforeImage(target));
                applyFields(target, d, p);
                String salaryRaw = value(d, "salary");
                if (salaryRaw != null) {
                    BigDecimal newSalary = parseMoney(salaryRaw);
                    if (newSalary != null && newSalary.compareTo(target.getBaseSalary()) != 0) {
                        BigDecimal old = target.getBaseSalary();
                        target.setBaseSalary(newSalary);
                        salaryHistoryRepo.save(com.empmg.platform.compensation.domain.SalaryHistory.builder()
                                .companyId(p.companyId())
                                .employeeId(target.getId())
                                .effectiveDate(LocalDate.now())
                                .oldSalary(old)
                                .newSalary(newSalary)
                                .reason("CSV import")
                                .changedByUserId(p.userId())
                                .createdAt(Instant.now())
                                .build());
                    }
                }
                target.setUpdatedAt(Instant.now());
                employeeRepo.save(target);
                updated++;
            } else {
                skipped++;
            }
        }

        ImportBatch batch = batchRepo.save(ImportBatch.builder()
                .companyId(p.companyId())
                .fileName(file.getOriginalFilename())
                .createdCount(created)
                .updatedCount(updated)
                .skippedCount(skipped)
                .errorCount(0)
                .undoData(writeJson(Map.of("entries", undo)))
                .createdByUserId(p.userId())
                .createdAt(Instant.now())
                .build());

        audit.record(p, p.companyId(), "EMPLOYEES_IMPORTED", "IMPORT_BATCH", batch.getId(),
                Map.of("created", created, "updated", updated, "skipped", skipped,
                        "file", String.valueOf(file.getOriginalFilename())));
        return new ImportResult(batch.getId(), created, updated, skipped, 0);
    }

    @Transactional
    public void undo(Long batchId, AuthPrincipal p) {
        salaryVisibility.assertCanChangeSalaries(p);
        ImportBatch batch = batchRepo.findByIdAndCompanyId(batchId, p.companyId())
                .orElseThrow(() -> new ApiException("Import not found.", 404));
        if (batch.getUndoneAt() != null) {
            throw new ApiException("This import was already undone.");
        }
        batchRepo.findTopByCompanyIdAndUndoneAtIsNullOrderByCreatedAtDesc(p.companyId())
                .filter(b -> !b.getId().equals(batchId))
                .ifPresent(b -> { throw new ApiException("Only the most recent import can be undone."); });

        List<Map<String, Object>> entries = readJson(batch.getUndoData());

        List<Long> createdIds = entries.stream()
                .filter(e -> "CREATED".equals(e.get("action")))
                .map(e -> ((Number) e.get("id")).longValue())
                .toList();

        // a created employee that already has pay records can't be safely deleted
        for (Long id : createdIds) {
            boolean hasPayRecords =
                    !salaryHistoryRepo.findByCompanyIdAndEmployeeIdOrderByEffectiveDateDescIdDesc(p.companyId(), id).isEmpty()
                            || !componentRepo.findByCompanyIdAndEmployeeIdOrderByIdAsc(p.companyId(), id).isEmpty();
            if (hasPayRecords) {
                throw new ApiException(
                        "This import can't be undone because the imported employees already have pay records. Adjust them manually instead.");
            }
        }

        for (Map<String, Object> entry : entries) {
            if (!"UPDATED".equals(entry.get("action"))) continue;
            Employee e = employeeRepo.findByIdAndCompanyId(((Number) entry.get("id")).longValue(), p.companyId())
                    .orElse(null);
            if (e == null) continue;
            e.setEmployeeCode(str(entry.get("employeeCode")));
            e.setFirstName(str(entry.get("firstName")));
            e.setLastName(str(entry.get("lastName")));
            e.setEmail(str(entry.get("email")));
            e.setPhone(str(entry.get("phone")));
            e.setJobRoleId(num(entry.get("jobRoleId")));
            e.setTierId(num(entry.get("tierId")));
            e.setManagerId(num(entry.get("managerId")));
            e.setTeam(str(entry.get("team")));
            e.setStartDate(entry.get("startDate") == null ? null : LocalDate.parse((String) entry.get("startDate")));
            e.setStatus(str(entry.get("status")));
            e.setBaseSalary(new BigDecimal((String) entry.get("baseSalary")));
            e.setUpdatedAt(Instant.now());
            employeeRepo.save(e);
        }
        for (Long id : createdIds) {
            employeeRepo.findByIdAndCompanyId(id, p.companyId()).ifPresent(employeeRepo::delete);
        }

        batch.setUndoneAt(Instant.now());
        batchRepo.save(batch);
        audit.record(p, p.companyId(), "IMPORT_UNDONE", "IMPORT_BATCH", batch.getId(), Map.of());
    }

    public record BatchInfo(Long id, String fileName, int created, int updated, int skipped, int errors,
                            boolean undone, Instant createdAt) {}

    @Transactional(readOnly = true)
    public List<BatchInfo> recentBatches(AuthPrincipal p) {
        return batchRepo.findTop10ByCompanyIdOrderByCreatedAtDesc(p.companyId()).stream()
                .map(b -> new BatchInfo(b.getId(), b.getFileName(), b.getCreatedCount(), b.getUpdatedCount(),
                        b.getSkippedCount(), b.getErrorCount(), b.getUndoneAt() != null, b.getCreatedAt()))
                .toList();
    }

    // ---------- export ----------

    @Transactional(readOnly = true)
    public byte[] export(AuthPrincipal p, boolean includeSalary) {
        List<Employee> employees = employeeRepo.findByCompanyIdOrderByFirstNameAscLastNameAsc(p.companyId());
        Map<Long, Tier> tiers = new HashMap<>();
        for (Tier t : tierRepo.findByCompanyIdOrderBySortOrderAscIdAsc(p.companyId())) tiers.put(t.getId(), t);
        Map<Long, JobRole> roles = new HashMap<>();
        for (JobRole r : jobRoleRepo.findByCompanyIdAndArchivedFalseOrderByNameAsc(p.companyId())) roles.put(r.getId(), r);
        Map<Long, String> names = new HashMap<>();
        for (Employee e : employees) names.put(e.getId(), e.getFirstName() + " " + e.getLastName());

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             CSVPrinter printer = new CSVPrinter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8),
                     CSVFormat.DEFAULT)) {
            List<String> header = new ArrayList<>(List.of("employee_code", "first_name", "last_name", "email",
                    "phone", "job_role", "team", "manager", "start_date", "status", "tier"));
            if (includeSalary) header.add("salary");
            printer.printRecord(header);
            for (Employee e : employees) {
                List<String> row = new ArrayList<>();
                row.add(CsvSafety.safe(e.getEmployeeCode()));
                row.add(CsvSafety.safe(e.getFirstName()));
                row.add(CsvSafety.safe(e.getLastName()));
                row.add(CsvSafety.safe(e.getEmail()));
                row.add(CsvSafety.safe(e.getPhone()));
                row.add(CsvSafety.safe(e.getJobRoleId() != null && roles.containsKey(e.getJobRoleId())
                        ? roles.get(e.getJobRoleId()).getName() : ""));
                row.add(CsvSafety.safe(e.getTeam()));
                row.add(CsvSafety.safe(e.getManagerId() != null ? names.get(e.getManagerId()) : ""));
                row.add(e.getStartDate() == null ? "" : e.getStartDate().toString());
                row.add(e.getStatus());
                row.add(CsvSafety.safe(e.getTierId() != null && tiers.containsKey(e.getTierId())
                        ? tiers.get(e.getTierId()).getName() : ""));
                if (includeSalary) row.add(e.getBaseSalary().toPlainString());
                printer.printRecord(row);
            }
            printer.flush();
            audit.record(p, p.companyId(), includeSalary ? "EMPLOYEES_EXPORTED_WITH_SALARY" : "EMPLOYEES_EXPORTED",
                    "EMPLOYEE", null, Map.of("count", employees.size()));
            return out.toByteArray();
        } catch (IOException ex) {
            throw new ApiException("Could not build the export file. Please try again.", 500);
        }
    }

    // ---------- internals ----------

    private record ParsedCsv(List<String> headers, List<CSVRecord> records) {}

    private ParsedCsv parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiException("Please choose a CSV file to upload.");
        try (Reader reader = new InputStreamReader(stripBom(file.getBytes()), StandardCharsets.UTF_8)) {
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .setTrim(true)
                    .build();
            try (CSVParser parsed = format.parse(reader)) {
                if (parsed.getHeaderNames().isEmpty()) {
                    throw new ApiException("That file has no header row. Use the template as a starting point.");
                }
                return new ParsedCsv(parsed.getHeaderNames(), parsed.getRecords());
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("That file couldn't be read as CSV. Save it as CSV (UTF-8) and try again.");
        }
    }

    private static java.io.ByteArrayInputStream stripBom(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new java.io.ByteArrayInputStream(bytes, 3, bytes.length - 3);
        }
        return new java.io.ByteArrayInputStream(bytes);
    }

    public static Map<String, String> autoMap(List<String> headers) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String h : headers) {
            String norm = normalize(h);
            String field = FIELDS.stream().filter(f -> normalize(f).equals(norm)).findFirst()
                    .orElseGet(() -> ALIASES.entrySet().stream()
                            .filter(e -> e.getValue().contains(norm))
                            .map(Map.Entry::getKey)
                            .findFirst().orElse(null));
            if (field != null && !mapping.containsValue(field)) {
                mapping.put(h, field);
            }
        }
        return mapping;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private List<RowResult> validateAll(ParsedCsv csv, Map<String, String> mapping, String duplicateMode,
                                        Long companyId) {
        List<RowResult> out = new ArrayList<>();
        Set<String> seenEmails = new HashSet<>();
        Set<String> seenCodes = new HashSet<>();
        int rowNo = 1;
        for (CSVRecord rec : csv.records()) {
            rowNo++;
            Map<String, String> d = new LinkedHashMap<>();
            for (var entry : mapping.entrySet()) {
                int idx = csv.headers().indexOf(entry.getKey());
                if (idx >= 0 && idx < rec.size() && entry.getValue() != null && !entry.getValue().isBlank()) {
                    d.putIfAbsent(entry.getValue(), rec.get(idx));
                }
            }
            List<String> errors = new ArrayList<>();

            if (isBlank(value(d, "first_name"))) errors.add("First name is required.");
            if (isBlank(value(d, "last_name"))) errors.add("Last name is required.");

            String email = value(d, "email");
            if (email != null && !email.contains("@")) errors.add("Email doesn't look right.");

            String dateRaw = value(d, "start_date");
            if (isBlank(dateRaw)) {
                errors.add("Start date is required.");
            } else if (parseDate(dateRaw) == null) {
                errors.add("Start date '" + dateRaw + "' isn't a date we understand. Use YYYY-MM-DD.");
            }

            String status = value(d, "status");
            if (status != null && !status.equalsIgnoreCase(Employee.ACTIVE)
                    && !status.equalsIgnoreCase(Employee.INACTIVE)) {
                errors.add("Status must be ACTIVE or INACTIVE.");
            }

            String tierName = value(d, "tier");
            if (tierName != null && tierRepo.findByCompanyIdAndNameIgnoreCase(companyId, tierName).isEmpty()) {
                errors.add("Tier '" + tierName + "' doesn't exist yet. Create it first, or leave tier blank.");
            }

            String mgrEmail = value(d, "manager_email");
            if (mgrEmail != null
                    && employeeRepo.findByCompanyIdAndEmailIgnoreCase(companyId, mgrEmail.toLowerCase()).isEmpty()) {
                errors.add("Manager '" + mgrEmail + "' isn't an employee yet. Import the manager first, or leave it blank.");
            }

            String salaryRaw = value(d, "salary");
            if (salaryRaw != null && parseMoney(salaryRaw) == null) {
                errors.add("Salary '" + salaryRaw + "' is not a number. Use something like 4500 or 4500.50.");
            }

            // does this row create a new employee, or touch an existing one?
            Employee existing = email != null
                    ? employeeRepo.findByCompanyIdAndEmailIgnoreCase(companyId, email.toLowerCase()).orElse(null)
                    : null;
            if (existing == null) {
                String code = value(d, "employee_code");
                if (code != null) {
                    existing = employeeRepo.findByCompanyIdAndEmployeeCodeIgnoreCase(companyId, code).orElse(null);
                }
            }
            String action = existing == null ? "CREATE"
                    : ("UPDATE".equals(duplicateMode) ? "UPDATE" : "SKIP");
            if ("CREATE".equals(action) && salaryRaw == null) {
                errors.add("Salary is required for a new employee. Enter 0 if you don't know it yet.");
            }

            // duplicates within the file
            String code = value(d, "employee_code");
            String emailKey = email == null ? null : email.toLowerCase();
            if (emailKey != null && !seenEmails.add(emailKey)) errors.add("This email appears twice in the file.");
            if (code != null && !code.isBlank() && !seenCodes.add(code.toLowerCase())) {
                errors.add("Employee code '" + code + "' appears twice in the file.");
            }

            String rowAction = errors.isEmpty() ? action : "ERROR";
            out.add(new RowResult(rowNo, d, rowAction, errors,
                    "SKIP".equals(rowAction) ? List.of("Already exists — skipped.") : List.of()));
        }
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String value(Map<String, String> d, String field) {
        String v = d.get(field);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"));

    private static LocalDate parseDate(String raw) {
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw.trim(), f);
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Parses money: 4500, 4,500, 1234,56, 12,34,567.89, currency symbols and spaces are all accepted. */
    static BigDecimal parseMoney(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.replaceAll("[\\p{Sc}\\sA-Za-z]", "");
        boolean hasComma = s.contains(","), hasDot = s.contains(".");
        if (hasComma && hasDot) {
            if (s.lastIndexOf('.') > s.lastIndexOf(',')) s = s.replace(",", "");
            else s = s.replace(".", "").replace(',', '.');
        } else if (hasComma) {
            int last = s.lastIndexOf(',');
            boolean decimalComma = last >= s.length() - 3 && s.indexOf(',') == last && !s.substring(0, last).contains(",");
            s = decimalComma ? s.replace(',', '.') : s.replace(",", "");
        }
        try {
            return new BigDecimal(s).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void applyFields(Employee e, Map<String, String> d, AuthPrincipal p) {
        String first = value(d, "first_name");
        String last = value(d, "last_name");
        if (first != null) e.setFirstName(first);
        if (last != null) e.setLastName(last);
        String email = value(d, "email");
        if (email != null) e.setEmail(email.toLowerCase());
        e.setPhone(value(d, "phone"));
        e.setEmployeeCode(value(d, "employee_code"));
        e.setTeam(value(d, "team"));

        String roleName = value(d, "job_role");
        if (roleName != null) {
            JobRole role = jobRoleRepo.findByCompanyIdAndNameIgnoreCaseAndArchivedFalse(p.companyId(), roleName)
                    .orElseGet(() -> jobRoleRepo.save(JobRole.builder()
                            .companyId(p.companyId()).name(roleName).archived(false)
                            .createdAt(Instant.now()).build()));
            e.setJobRoleId(role.getId());
        }

        String tierName = value(d, "tier");
        if (tierName != null) {
            Tier tier = tierRepo.findByCompanyIdAndNameIgnoreCase(p.companyId(), tierName)
                    .orElseThrow(() -> new ApiException(
                            "Tier '" + tierName + "' doesn't exist yet. Create the tier first, or leave it blank."));
            e.setTierId(tier.getId());
        }

        String mgrEmail = value(d, "manager_email");
        if (mgrEmail != null) {
            Employee mgr = employeeRepo.findByCompanyIdAndEmailIgnoreCase(p.companyId(), mgrEmail.toLowerCase())
                    .orElseThrow(() -> new ApiException(
                            "Manager '" + mgrEmail + "' isn't an employee yet. Import the manager first, or leave it blank."));
            e.setManagerId(mgr.getId());
        }

        String dateRaw = value(d, "start_date");
        if (dateRaw != null) e.setStartDate(parseDate(dateRaw));
        String status = value(d, "status");
        if (status != null) e.setStatus(status.toUpperCase());
    }

    private static Map<String, Object> beforeImage(Employee e) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("action", "UPDATED");
        before.put("id", e.getId());
        before.put("employeeCode", e.getEmployeeCode());
        before.put("firstName", e.getFirstName());
        before.put("lastName", e.getLastName());
        before.put("email", e.getEmail());
        before.put("phone", e.getPhone());
        before.put("jobRoleId", e.getJobRoleId());
        before.put("tierId", e.getTierId());
        before.put("managerId", e.getManagerId());
        before.put("team", e.getTeam());
        before.put("startDate", e.getStartDate() == null ? null : e.getStartDate().toString());
        before.put("status", e.getStatus());
        before.put("baseSalary", e.getBaseSalary().toPlainString());
        return before;
    }

    private static String writeJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialize import snapshot", e);
        }
    }

    private static List<Map<String, Object>> readJson(String json) {
        try {
            if (json == null) return List.of();
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {})
                    .get("entries") == null ? List.of()
                    : JSON.convertValue(JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {}).get("entries"),
                            new TypeReference<List<Map<String, Object>>>() {});
        } catch (IOException e) {
            throw new ApiException("The undo information for this import is unreadable.", 500);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long num(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }
}

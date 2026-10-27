package com.empmg.platform.report;

import com.empmg.platform.common.ApiException;
import com.empmg.platform.common.CsvSafety;
import com.empmg.platform.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/payroll-summary")
    public ReportService.Table payrollSummary(@RequestParam(defaultValue = "12") int months) {
        return reportService.payrollSummary(SecurityUtils.current(), Math.min(Math.max(months, 1), 36));
    }

    @GetMapping("/cost-by")
    public ReportService.Table costBy(@RequestParam String group) {
        return reportService.costBy(SecurityUtils.current(), group);
    }

    @GetMapping("/salary-history")
    public ReportService.Table salaryHistory(@RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to) {
        return reportService.salaryHistory(SecurityUtils.current(), from, to);
    }

    @GetMapping("/variable-pay")
    public ReportService.Table variablePay() {
        return reportService.variablePay(SecurityUtils.current());
    }

    /** CSV download of any report: /api/reports/{name}/csv */
    @GetMapping("/{name}/csv")
    public ResponseEntity<byte[]> csv(@PathVariable String name,
                                      @RequestParam(required = false) String group,
                                      @RequestParam(required = false) LocalDate from,
                                      @RequestParam(required = false) LocalDate to,
                                      @RequestParam(defaultValue = "12") int months,
                                      HttpServletRequest request) {
        ReportService.Table table = switch (name) {
            case "payroll-summary" -> reportService.payrollSummary(SecurityUtils.current(), Math.min(Math.max(months, 1), 36));
            case "cost-by-tier" -> reportService.costBy(SecurityUtils.current(), "tier");
            case "cost-by-role" -> reportService.costBy(SecurityUtils.current(), "role");
            case "cost-by-team" -> reportService.costBy(SecurityUtils.current(), "team");
            case "salary-history" -> reportService.salaryHistory(SecurityUtils.current(), from, to);
            case "variable-pay" -> reportService.variablePay(SecurityUtils.current());
            default -> throw new ApiException("That report doesn't exist.");
        };
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", table.columns().stream().map(ReportController::cell).toList())).append("\r\n");
        for (List<String> row : table.rows()) {
            sb.append(String.join(",", row.stream().map(ReportController::cell).toList())).append("\r\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** CSV safety for text cells; numbers and dates pass through unchanged. */
    private static String cell(String value) {
        if (value == null) return "";
        String safe = value.matches("-?\\d+(\\.\\d+)?") || value.matches("\\d{4}-\\d{2}-\\d{2}")
                || value.matches("\\d{4}-\\d{2}") || value.chars().allMatch(Character::isDigit)
                ? value : CsvSafety.safe(value);
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}

package com.empmg.platform.compensation;

import com.empmg.platform.BaseIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.empmg.platform.compensation.repo.SalaryHistoryRepo;
import com.empmg.platform.compensation.service.RaiseService;
import com.empmg.platform.security.AccessRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RaiseFlowIT extends BaseIT {

    @Autowired SalaryHistoryRepo salaryHistoryRepo;

    private record Setup(Ctx cfo, long emp1, long emp2, long tierId) {}

    private Setup setup() throws Exception {
        Ctx cfo = onboard("RaiseCo");
        JsonNode tier = createTier(cfo, "Tier 1", "1000", "3000");
        long tierId = tier.get("id").asLong();
        JsonNode e1 = createEmployee(cfo, "Asha", "Verma", "asha@raiseco.test", new BigDecimal("2000"), tierId, null);
        JsonNode e2 = createEmployee(cfo, "Ben", "Okafor", "ben@raiseco.test", new BigDecimal("1000"), null, null);
        return new Setup(cfo, e1.get("id").asLong(), e2.get("id").asLong(), tierId);
    }

    @Test
    void previewShowsCorrectTotals() throws Exception {
        Setup s = setup();
        JsonNode preview = apiPost(s.cfo(), "/api/raises/preview", Map.of(
                "employeeIds", List.of(s.emp1(), s.emp2()),
                "percent", 10, "effectiveDate", today().toString(), "reason", "annual review"));
        assertEquals(2, preview.get("affected").asInt());
        // old total 3000 → new total 3300
        assertEquals(0, new BigDecimal("3000").compareTo(preview.get("totalOldMonthly").decimalValue()));
        assertEquals(0, new BigDecimal("3300").compareTo(preview.get("totalNewMonthly").decimalValue()));
        assertEquals(0, new BigDecimal("300").compareTo(preview.get("monthlyDelta").decimalValue()));
        assertEquals(0, new BigDecimal("3600").compareTo(preview.get("annualDelta").decimalValue()));
    }

    @Test
    void cfoRaiseEffectiveTodayAppliesImmediately() throws Exception {
        Setup s = setup();
        JsonNode raise = apiPost(s.cfo(), "/api/raises", Map.of(
                "employeeIds", List.of(s.emp1(), s.emp2()),
                "percent", 10, "effectiveDate", today().toString(), "reason", "annual review"));
        assertEquals("APPLIED", raise.get("status").asText());

        // salaries updated
        assertEquals(0, new BigDecimal("2200.00").compareTo(
                getJson(s.cfo(), "/api/employees/" + s.emp1()).get("baseSalary").decimalValue()));

        // salary history appended, nothing edited
        // (entries are ordered newest-first: [0] = the raise, [1] = the initial salary)
        var history = salaryHistoryRepo.findByCompanyIdAndEmployeeIdOrderByEffectiveDateDescIdDesc(
                s.cfo().companyId(), s.emp1());
        assertEquals(2, history.size()); // initial + raise
        assertEquals(0, new BigDecimal("2000").compareTo(history.get(0).getOldSalary()));
        assertEquals(0, new BigDecimal("2200.00").compareTo(history.get(0).getNewSalary()));
        assertEquals(0, new BigDecimal("10.0000").compareTo(history.get(0).getPercent()));
    }

    @Test
    void futureDatedRaiseWaitsForItsDate() throws Exception {
        Setup s = setup();
        JsonNode raise = apiPost(s.cfo(), "/api/raises", Map.of(
                "employeeIds", List.of(s.emp1()),
                "percent", 10, "effectiveDate", today().plusDays(30).toString(), "reason", "future"));
        assertEquals("APPROVED", raise.get("status").asText());
        assertEquals(0, new BigDecimal("2000").compareTo(
                getJson(s.cfo(), "/api/employees/" + s.emp1()).get("baseSalary").decimalValue()));

        // the scheduler applies it once the effective date arrives — simulate time
        // passing by rewinding the date, exactly what a raise dated yesterday looks like
        var stored = raiseRepo.findById(raise.get("id").asLong()).orElseThrow();
        stored.setEffectiveDate(LocalDate.now().minusDays(1));
        raiseRepo.save(stored);
        raiseService.applyIfDue(stored, null);
        assertEquals(0, new BigDecimal("2200.00").compareTo(
                getJson(s.cfo(), "/api/employees/" + s.emp1()).get("baseSalary").decimalValue()));
    }

    @Test
    void hrProposesAndCfoApproves() throws Exception {
        Setup s = setup();
        Ctx hr = createUser(s.cfo(), AccessRole.HR, null);

        JsonNode proposed = apiPost(hr, "/api/raises", Map.of(
                "employeeIds", List.of(s.emp1()),
                "percent", 5, "effectiveDate", today().toString(), "reason", "HR proposal"));
        assertEquals("PENDING", proposed.get("status").asText());

        JsonNode approved = apiPost(s.cfo(), "/api/raises/" + proposed.get("id").asLong() + "/approve", null);
        assertEquals("APPLIED", approved.get("status").asText());
    }

    @Test
    void capAtTierMaximum() throws Exception {
        Setup s = setup(); // emp1 is in Tier 1 with max 3000
        JsonNode raise = apiPost(s.cfo(), "/api/raises", Map.of(
                "employeeIds", List.of(s.emp1()),
                "percent", 100, "effectiveDate", today().toString(), "reason", "big jump",
                "capPolicy", "CAP"));
        assertEquals("APPLIED", raise.get("status").asText());
        assertEquals(1, raise.get("cappedCount").asInt());
        // 2000 +100% = 4000 → capped at 3000
        assertEquals(0, new BigDecimal("3000.00").compareTo(
                getJson(s.cfo(), "/api/employees/" + s.emp1()).get("baseSalary").decimalValue()));
    }

    @Test
    void mistakeCanBeReversedWithoutEditingHistory() throws Exception {
        Setup s = setup();
        JsonNode raise = apiPost(s.cfo(), "/api/raises", Map.of(
                "employeeIds", List.of(s.emp1()),
                "percent", 10, "effectiveDate", today().toString(), "reason", "oops soon"));
        long emp1 = s.emp1();

        // reverse the raise entry (the newest history row for emp1)
        var history = salaryHistoryRepo.findByCompanyIdAndEmployeeIdOrderByEffectiveDateDescIdDesc(
                s.cfo().companyId(), emp1);
        long raiseEntryId = history.get(0).getId();
        apiPost(s.cfo(), "/api/salary-history/" + raiseEntryId + "/reverse", Map.of("reason", "wrong person"));

        assertEquals(0, new BigDecimal("2000.00").compareTo(
                getJson(s.cfo(), "/api/employees/" + emp1).get("baseSalary").decimalValue()));
        var after = salaryHistoryRepo.findByCompanyIdAndEmployeeIdOrderByEffectiveDateDescIdDesc(
                s.cfo().companyId(), emp1);
        assertEquals(3, after.size());
        assertNotNull(after.get(0).getReversalOfId());
        // original entry untouched (immutable history)
        assertEquals(0, new BigDecimal("2200.00").compareTo(after.get(1).getNewSalary()));
    }

    @Test
    void employeeRoleCannotCreateRaises() throws Exception {
        Setup s = setup();
        JsonNode worker = createEmployee(s.cfo(), "W", "I", "worker@raiseco.test",
                new BigDecimal("1000"), null, null);
        Ctx emp = createUser(s.cfo(), AccessRole.EMPLOYEE, worker.get("id").asLong());
        assertEquals(403, statusOf(emp, "POST", "/api/raises", Map.of(
                "employeeIds", List.of(s.emp1()),
                "percent", 10, "effectiveDate", today().toString(), "reason", "sneaky")));
    }

    @Autowired RaiseService raiseService;
    @Autowired com.empmg.platform.compensation.repo.RaiseRepo raiseRepo;
}

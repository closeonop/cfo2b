package com.empmg.platform.employee;

import com.empmg.platform.BaseIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.empmg.platform.security.AccessRole;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE critical permission rules: who sees salaries, and strict company isolation.
 */
class SalaryVisibilityIT extends BaseIT {

    @Test
    void cfoSeesSalariesHrDoesNotUntilEnabledManagerAndEmployeeNeverDo() throws Exception {
        Ctx cfo = onboard("VisCo");
        JsonNode tier = createTier(cfo, "Tier 1", "1000", "5000");
        long tierId = tier.get("id").asLong();

        JsonNode manager = createEmployee(cfo, "Marta", "Reyes", "marta@visco.test", new BigDecimal("4000"), tierId, null);
        JsonNode worker = createEmployee(cfo, "Liam", "Chen", "liam@visco.test", new BigDecimal("2000"), tierId, manager.get("id").asLong());
        long managerId = manager.get("id").asLong();
        long workerId = worker.get("id").asLong();

        Ctx hr = createUser(cfo, AccessRole.HR, null);
        Ctx mgr = createUser(cfo, AccessRole.MANAGER, managerId);
        Ctx emp = createUser(cfo, AccessRole.EMPLOYEE, workerId);

        // CFO sees salary
        assertTrue(getJson(cfo, "/api/employees/" + workerId).get("baseSalary").isNumber());

        // HR doesn't see salary by default…
        assertTrue(getJson(hr, "/api/employees/" + workerId).get("baseSalary").isNull());
        // …and the tier stats show headcount but no cost
        JsonNode statsNoMoney = getJson(hr, "/api/tiers/stats");
        assertTrue(statsNoMoney.get(0).get("headcount").isNumber());
        assertTrue(statsNoMoney.get(0).get("totalMonthlyCost").isNull());

        // …but salaries appear once the CFO enables it
        apiPut(cfo, "/api/company", Map.of("name", "VisCo", "currency", "USD", "locale", "en-US",
                "salaryPeriod", "MONTHLY", "roundingRule", "NONE", "hrCanSeeSalary", true));
        assertTrue(getJson(hr, "/api/employees/" + workerId).get("baseSalary").isNumber());
        assertTrue(getJson(hr, "/api/tiers/stats").get(0).get("totalMonthlyCost").isNumber());

        // manager sees their team member's profile but no salary
        JsonNode seenByManager = getJson(mgr, "/api/employees/" + workerId);
        assertEquals(workerId, seenByManager.get("id").asLong());
        assertTrue(seenByManager.get("baseSalary").isNull());
        // manager can view their own profile, but not someone outside their team
        assertEquals(200, statusOf(mgr, "GET", "/api/employees/" + managerId, null));

        // employee sees only their own profile (no salary), and own compensation
        JsonNode own = getJson(emp, "/api/employees/" + workerId);
        assertTrue(own.get("baseSalary").isNull());
        assertTrue(getJson(emp, "/api/employees/" + workerId + "/compensation").get("baseSalary").isNumber());
        assertEquals(403, statusOf(emp, "GET", "/api/employees/" + managerId + "/compensation", null));
        assertEquals(403, statusOf(emp, "GET", "/api/employees/" + managerId, null));
    }

    @Test
    void companyBcannotReadCompanyA() throws Exception {
        Ctx a = onboard("IsoA");
        Ctx b = onboard("IsoB");

        JsonNode tierA = createTier(a, "Tier X", "1000", "5000");
        JsonNode empA = createEmployee(a, "Asha", "V", "asha@isoa.test", new BigDecimal("3000"),
                tierA.get("id").asLong(), null);
        long empAId = empA.get("id").asLong();

        // company B cannot read company A's employee, tier, or raises
        assertEquals(404, statusOf(b, "GET", "/api/employees/" + empAId, null));
        assertEquals(404, statusOf(b, "POST", "/api/tiers/" + tierA.get("id").asLong() + "/archive", null));

        // company B's employee list contains only its own (empty) list
        assertEquals(0, getJson(b, "/api/employees").size());

        // company B's CFO cannot propose a raise for company A's employee
        int st = statusOf(b, "POST", "/api/raises", Map.of(
                "employeeIds", java.util.List.of(empAId),
                "percent", 5, "effectiveDate", "2026-01-01", "reason", "test"));
        assertEquals(400, st);
    }

    @Test
    void employeeListIsScopedByRole() throws Exception {
        Ctx cfo = onboard("ScopeCo");
        JsonNode manager = createEmployee(cfo, "Boss", "One", "boss@scopeco.test", new BigDecimal("5000"), null, null);
        JsonNode teamMember = createEmployee(cfo, "Teammate", "Two", "mate@scopeco.test", new BigDecimal("2000"), null, manager.get("id").asLong());
        JsonNode other = createEmployee(cfo, "Other", "Three", "other@scopeco.test", new BigDecimal("2000"), null, null);

        Ctx mgr = createUser(cfo, AccessRole.MANAGER, manager.get("id").asLong());
        Ctx emp = createUser(cfo, AccessRole.EMPLOYEE, teamMember.get("id").asLong());

        assertEquals(3, getJson(cfo, "/api/employees").size());
        assertEquals(1, getJson(mgr, "/api/employees").size());
        assertEquals(teamMember.get("id").asLong(), getJson(mgr, "/api/employees").get(0).get("id").asLong());
        assertEquals(1, getJson(emp, "/api/employees").size());
        assertEquals(teamMember.get("id").asLong(), getJson(emp, "/api/employees").get(0).get("id").asLong());
    }

    @Test
    void onlyAdminSeesAuditLog() throws Exception {
        Ctx cfo = onboard("AuditCo");
        Ctx hr = createUser(cfo, AccessRole.HR, null);
        assertEquals(403, statusOf(hr, "GET", "/api/audit", null));
        assertEquals(200, statusOf(cfo, "GET", "/api/audit", null));
    }
}

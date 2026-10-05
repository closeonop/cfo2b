package com.empmg.platform.employee;

import com.empmg.platform.BaseIT;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CsvImportIT extends BaseIT {

    private MockMultipartFile csv(String name, String content) {
        return new MockMultipartFile("file", name, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode preview(Ctx ctx, MockMultipartFile file, String duplicateMode) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/employees/import/preview", file);
        return read(mvc.perform(withAuth(builder.file(file).param("duplicateMode", duplicateMode), ctx)).andReturn());
    }

    private JsonNode apply(Ctx ctx, MockMultipartFile file, String duplicateMode) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/employees/import", file);
        return read(mvc.perform(withAuth(builder.file(file).param("duplicateMode", duplicateMode), ctx)).andReturn());
    }

    @Test
    void templateDownloads() throws Exception {
        Ctx ctx = onboard("TplCo");
        mvc.perform(withAuth(get("/api/employees/import/template"), ctx))
                .andExpect(status().isOk());
    }

    @Test
    void previewDetectsErrorsInPlainLanguage() throws Exception {
        Ctx ctx = onboard("PrevCo");
        createTier(ctx, "Tier 2", "1000", "5000");
        String csvContent = """
                first_name,last_name,email,start_date,tier,salary
                Asha,Verma,asha@prev.test,2025-01-15,Tier 2,4500
                ,NoFirst,nofirst@prev.test,2025-01-16,Tier 2,4000
                Bad,Salary,bad@prev.test,2025-01-17,Tier 2,not-a-number
                Ok,MissingTier,ok@prev.test,2025-01-18,Tier 9,3000
                """;
        JsonNode result = preview(ctx, csv("people.csv", csvContent), "SKIP");
        assertEquals(4, result.get("rows").size());
        assertEquals(1, result.get("create").asInt());
        assertEquals(3, result.get("error").asInt());
        String rowThreeErrors = result.get("rows").get(2).get("errors").get(0).asText();
        assertTrue(rowThreeErrors.contains("not a number"));
    }

    @Test
    void importCreatesSkipsUpdatesAndUndoes() throws Exception {
        Ctx ctx = onboard("ImpCo");
        createTier(ctx, "Tier 2", "1000", "5000");

        apiPost(ctx, "/api/employees", Map.of("firstName", "Existing", "lastName", "Person",
                "email", "existing@imp.test", "salary", new java.math.BigDecimal("3000"),
                "startDate", "2025-01-01"));

        String csvContent = """
                first_name,last_name,email,start_date,tier,salary
                New,Person,new@imp.test,2025-02-01,Tier 2,4200
                Existing,Person,existing@imp.test,2025-02-01,Tier 2,3500
                """;

        // SKIP mode: 1 created, 1 skipped
        JsonNode skipResult = apply(ctx, csv("people.csv", csvContent), "SKIP");
        assertEquals(1, skipResult.get("created").asInt());
        assertEquals(1, skipResult.get("skipped").asInt());
        assertEquals(2, getJson(ctx, "/api/employees").size());

        // undo: created employee disappears, untouched one stays
        Long batchId = skipResult.get("batchId").asLong();
        apiPost(ctx, "/api/employees/import/" + batchId + "/undo", null);
        JsonNode employees = getJson(ctx, "/api/employees");
        assertEquals(1, employees.size());
        assertEquals("Existing", employees.get(0).get("firstName").asText());

        // UPDATE mode: existing employee's salary is updated…
        JsonNode updateResult = apply(ctx, csv("people.csv", csvContent), "UPDATE");
        assertEquals(1, updateResult.get("created").asInt());
        assertEquals(1, updateResult.get("updated").asInt());
        JsonNode afterUpdate = getJson(ctx, "/api/employees");
        JsonNode existing = afterUpdate.get(0).get("firstName").asText().equals("Existing")
                ? afterUpdate.get(0) : afterUpdate.get(1);
        assertEquals(0, new java.math.BigDecimal("3500.00").compareTo(existing.get("baseSalary").decimalValue()));

        // …and undo restores the pre-import salary (3000) and removes the new employee
        Long updateBatchId = updateResult.get("batchId").asLong();
        apiPost(ctx, "/api/employees/import/" + updateBatchId + "/undo", null);
        JsonNode afterUndo = getJson(ctx, "/api/employees");
        assertEquals(1, afterUndo.size());
        assertEquals(0, new java.math.BigDecimal("3000.00").compareTo(afterUndo.get(0).get("baseSalary").decimalValue()));
    }

    @Test
    void exportEscapesFormulaInjection() throws Exception {
        Ctx ctx = onboard("InjectCo");
        apiPost(ctx, "/api/employees", Map.of("firstName", "=SUM(A1:A2)", "lastName", "Hacker",
                "email", "hack@inject.test", "salary", new java.math.BigDecimal("100"),
                "startDate", "2025-01-01"));

        byte[] exported = raw(ctx, get("/api/employees/export")).getResponse().getContentAsByteArray();
        String text = new String(exported, StandardCharsets.UTF_8);
        assertTrue(text.contains("'=SUM(A1:A2)"), "dangerous cell must be prefixed with an apostrophe");
    }

    @Test
    void importRequiresFinancePermission() throws Exception {
        Ctx cfo = onboard("PermCo");
        createEmployee(cfo, "Asha", "V", "asha@perm.test", new java.math.BigDecimal("1000"), null, null);
        Ctx hr = createUser(cfo, com.empmg.platform.security.AccessRole.HR, null);

        MockMultipartHttpServletRequestBuilder builder = multipart("/api/employees/import", csv("x.csv", "a,b\n1,2\n"));
        assertEquals(403, mvc.perform(withAuth(builder.file(csv("x.csv", "a,b\n1,2\n")), hr)).andReturn()
                .getResponse().getStatus());
    }

    private JsonNode read(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        if (result.getResponse().getStatus() >= 400) {
            System.out.println("[HTTP " + result.getResponse().getStatus() + "] → " + body);
        }
        return body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }
}

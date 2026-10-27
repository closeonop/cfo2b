package com.empmg.platform;

import com.empmg.platform.security.AccessRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Base for integration tests: real PostgreSQL (the docker compose database,
 * `empdb_test` — see DECISIONS.md for why this replaced Testcontainers), the
 * real security filter chain, and the real encryption key. Every test builds
 * its own company (unique emails), so tests don't interfere with each other.
 *
 * Override the connection with TEST_DB_URL / TEST_DB_USERNAME / TEST_DB_PASSWORD.
 * Flyway migrates and the app validates the schema on every run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseIT {

    public static final String PASSWORD = "Password123";
    static final String TEST_KEY = "/v3o9AsnJZvJhjQHisPHU3HkESLK1ZESD+MbPgEwGUU=";
    static final String ACCESS_SECRET = "test-access-secret-0123456789abcdef0123456789abcdef";
    static final String REFRESH_SECRET = "test-refresh-secret-0123456789abcdef0123456789abcdef";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> env("TEST_DB_URL", "jdbc:postgresql://localhost:5433/empdb_test"));
        r.add("spring.datasource.username", () -> env("TEST_DB_USERNAME", "emp"));
        r.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "empdevpw"));
        r.add("app.encryption.key", () -> TEST_KEY);
        r.add("app.jwt.access-secret", () -> ACCESS_SECRET);
        r.add("app.jwt.refresh-secret", () -> REFRESH_SECRET);
        r.add("app.mail.enabled", () -> "false");
        r.add("storage.endpoint", () -> env("TEST_STORAGE_ENDPOINT", "http://localhost:9000"));
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;

    /** A signed-in user: access token + company id. */
    public record Ctx(String email, String token, Long companyId) {}

    // ---------- auth helpers ----------

    public Ctx onboard(String companyName) throws Exception {
        return onboardAs(companyName, uniqueEmail("admin"));
    }

    public Ctx onboardAs(String companyName, String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "companyName", companyName, "email", email, "password", PASSWORD))))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException("onboarding failed: " + result.getResponse().getContentAsString());
        }
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        return new Ctx(email, node.get("accessToken").asText(), node.get("user").get("companyId").asLong());
    }

    /** Logs in and resolves the company id from /api/auth/me. Returns null on bad credentials. */
    public Ctx loginAs(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andReturn();
        if (result.getResponse().getStatus() != 200) return null;
        String token = json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
        MvcResult me = mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andReturn();
        Long companyId = json.readTree(me.getResponse().getContentAsString())
                .path("company").path("id").asLong();
        return new Ctx(email, token, companyId);
    }

    /** Admin creates a user account for the given role, optionally linked to an employee. */
    public Ctx createUser(Ctx admin, AccessRole role, Long employeeId) throws Exception {
        String email = role.name().toLowerCase() + "-" + unique() + "@test.io";
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", PASSWORD);
        body.put("role", role.name());
        if (employeeId != null) body.put("employeeId", employeeId);
        apiPost(admin, "/api/users", body);
        return loginAs(email, PASSWORD);
    }

    // ---------- data helpers ----------

    public JsonNode createTier(Ctx ctx, String name, String min, String max) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        if (min != null) body.put("minSalary", new BigDecimal(min));
        if (max != null) body.put("maxSalary", new BigDecimal(max));
        return apiPost(ctx, "/api/tiers", body);
    }

    public JsonNode createEmployee(Ctx ctx, String first, String last, String email,
                                   BigDecimal salary, Long tierId, Long managerId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", first);
        body.put("lastName", last);
        if (email != null) body.put("email", email);
        body.put("salary", salary);
        body.put("startDate", "2025-01-15");
        if (tierId != null) body.put("tierId", tierId);
        if (managerId != null) body.put("managerId", managerId);
        return apiPost(ctx, "/api/employees", body);
    }

    // ---------- request helpers ----------

    public JsonNode apiPost(Ctx ctx, String path, Object body) throws Exception {
        return read(mvc.perform(withAuth(post(path), ctx)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body == null ? Map.of() : body)))
                .andReturn());
    }

    public JsonNode apiPut(Ctx ctx, String path, Object body) throws Exception {
        return read(mvc.perform(withAuth(put(path), ctx)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body == null ? Map.of() : body)))
                .andReturn());
    }

    public JsonNode getJson(Ctx ctx, String path) throws Exception {
        return read(mvc.perform(withAuth(get(path), ctx)).andReturn());
    }

    public int statusOf(Ctx ctx, String method, String path, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = switch (method.toUpperCase()) {
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> get(path);
        };
        if (body != null) builder = builder.contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        MvcResult result = mvc.perform(withAuth(builder, ctx)).andReturn();
        if (result.getResponse().getStatus() >= 400) {
            System.out.println("[HTTP " + result.getResponse().getStatus() + "] " + method + " " + path
                    + " → " + result.getResponse().getContentAsString());
        }
        return result.getResponse().getStatus();
    }

    public MvcResult raw(Ctx ctx, MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(withAuth(builder, ctx)).andReturn();
    }

    private static JsonNode read(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        if (result.getResponse().getStatus() >= 400) {
            // loud, but invaluable when a test fails on an unexpected response
            System.out.println("[HTTP " + result.getResponse().getStatus() + "] "
                    + result.getRequest().getMethod() + " " + result.getRequest().getRequestURI() + " → " + body);
        }
        return body == null || body.isBlank() ? new ObjectMapper().createObjectNode() : new ObjectMapper().readTree(body);
    }

    protected static MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder, Ctx ctx) {
        return ctx == null ? builder : builder.header("Authorization", "Bearer " + ctx.token());
    }

    protected static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + unique() + "@test.io";
    }

    protected static LocalDate today() {
        return LocalDate.now();
    }
}

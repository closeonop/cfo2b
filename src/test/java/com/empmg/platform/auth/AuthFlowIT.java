package com.empmg.platform.auth;

import com.empmg.platform.BaseIT;
import com.empmg.platform.auth.repo.PasswordResetTokenRepo;
import com.empmg.platform.user.repo.UserRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowIT extends BaseIT {

    @Autowired UserRepo userRepo;
    @Autowired PasswordResetTokenRepo resetTokenRepo;

    @Test
    void onboardingThenLoginThenMe() throws Exception {
        Ctx ctx = onboard("Acme");
        assertTrue(ctx.token() != null && !ctx.token().isBlank());
        Ctx logged = loginAs(ctx.email(), PASSWORD);
        assertEquals(ctx.companyId(), logged.companyId());

        mvc.perform(withAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/auth/me"), logged))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    void wrongPasswordGivesGenericError() throws Exception {
        Ctx ctx = onboard("GenericErr");
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", ctx.email(), "password", "WrongPass123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Email or password is incorrect."));
    }

    @Test
    void unknownEmailGivesSameGenericError() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "ghost@test.io", "password", "Whatever123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Email or password is incorrect."));
    }

    @Test
    void sixFailedAttemptsAreBlocked() throws Exception {
        Ctx ctx = onboard("RateLimit");
        for (int i = 0; i < 5; i++) {
            statusOf(null, "POST", "/api/auth/login", Map.of("email", ctx.email(), "password", "WrongPass123"));
        }
        // 6th attempt — even with the correct password — is rate limited
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", ctx.email(), "password", PASSWORD))))
                .andExpect(status().is(429));
    }

    @Test
    void weakPasswordsRejectedAtOnboarding() throws Exception {
        mvc.perform(post("/api/auth/onboarding").contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "companyName", "Weak", "email", uniqueEmail("weak"), "password", "short"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refreshRotatesAndRequiresCustomHeader() throws Exception {
        Ctx ctx = onboard("Refresh");
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", ctx.email(), "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        String cookie = login.getResponse().getHeader("Set-Cookie");
        assertNotNull(cookie);
        assertTrue(cookie.startsWith("emp_rt="));
        // browsers send only name=value pairs back, not the attributes
        String token = cookie.split(";", 2)[0].substring("emp_rt=".length());

        // CSRF guard: no X-Requested-With → rejected
        mvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("emp_rt", token)))
                .andExpect(status().isUnauthorized());

        // with the header → rotated session
        var rotated = mvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("emp_rt", token))
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(200, rotated.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                rotated.getResponse().getContentAsString().contains("accessToken"));
        mvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("emp_rt", token))
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordResetFlow() throws Exception {
        Ctx ctx = onboard("ResetFlow");

        // unknown email → same friendly response (no account enumeration)
        mvc.perform(post("/api/auth/forgot-password").contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "nobody@test.io"))))
                .andExpect(status().isOk());

        // create a reset token directly (mail is disabled in tests)
        String raw = "test-reset-token-" + unique();
        var user = userRepo.findByEmailIgnoreCase(ctx.email()).orElseThrow();
        resetTokenRepo.save(com.empmg.platform.auth.domain.PasswordResetToken.builder()
                .userId(user.getId())
                .tokenHash(sha256(raw))
                .expiresAt(Instant.now().plusSeconds(1800))
                .createdAt(Instant.now())
                .build());

        String newPassword = "NewPassword456";
        mvc.perform(post("/api/auth/reset-password").contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", raw, "newPassword", newPassword))))
                .andExpect(status().isOk());

        // old password no longer works, new one does
        assertEquals(401, statusOf(ctx, "GET", "/api/employees", null));
        assertEquals(401, statusOf(null, "POST", "/api/auth/login",
                Map.of("email", ctx.email(), "password", PASSWORD)));
        assertNotNull(loginAs(ctx.email(), newPassword));

        // the token is single-use
        mvc.perform(post("/api/auth/reset-password").contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", raw, "newPassword", "AnotherPass789"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void endpointsRequireAuth() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/employees"))
                .andExpect(status().isUnauthorized());
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    @org.junit.jupiter.api.Test
    void fixedVerificationCodeIsDisabledByDefault() throws Exception {
        var admin=onboard("Verification off");
        long employee=createEmployee(admin,"Setup","Demo",uniqueEmail("setup"),java.math.BigDecimal.ZERO,null,null).path("id").asLong();
        String email=uniqueEmail("restricted");
        var created=apiPost(admin,"/api/users",java.util.Map.of("email",email,"role","EMPLOYEE","employeeId",employee));
        var pending=loginAs(email,created.path("temporaryPassword").asText());
        assertEquals(503,statusOf(pending,"POST","/api/auth/welcome/request-code",java.util.Map.of()));
        assertEquals(503,statusOf(pending,"POST","/api/auth/welcome/verify-code",java.util.Map.of("code","123456")));
        assertEquals(403,statusOf(pending,"GET","/api/hr/home",null));
    }
}

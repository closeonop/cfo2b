package com.empmg.platform.auth;

import com.empmg.platform.auth.dto.ChangePasswordRequest;
import com.empmg.platform.auth.dto.ForgotPasswordRequest;
import com.empmg.platform.auth.dto.LoginRequest;
import com.empmg.platform.auth.dto.OnboardingRequest;
import com.empmg.platform.auth.dto.ResetPasswordRequest;
import com.empmg.platform.auth.dto.TokenResponse;
import com.empmg.platform.auth.service.AuthService;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.common.AppProperties;
import com.empmg.platform.company.domain.Company;
import com.empmg.platform.company.repo.CompanyRepo;
import com.empmg.platform.employee.repo.EmployeeRepo;
import com.empmg.platform.security.AuthPrincipal;
import com.empmg.platform.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    public static final String REFRESH_COOKIE = "emp_rt";

    private final AuthService authService;
    private final CompanyRepo companyRepo;
    private final EmployeeRepo employeeRepo;
    private final AppProperties props;

    @PostMapping("/onboarding")
    public ResponseEntity<TokenResponse> onboarding(@Valid @RequestBody OnboardingRequest req,
                                                    HttpServletRequest http) {
        var result = authService.onboarding(req);
        return ResponseEntity.ok()
                .header("Set-Cookie", refreshCookie(result.response().user().id(), http, result.refreshToken()).toString())
                .body(result.response());
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        var result = authService.login(req, clientIp(http));
        return ResponseEntity.ok()
                .header("Set-Cookie", refreshCookie(result.response().user().id(), http, result.refreshToken()).toString())
                .body(result.response());
    }

    /** Rotates the refresh token. The custom header is a CSRF guard: cross-site requests can't set it. */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(value = REFRESH_COOKIE, required = false) String cookie,
            @RequestHeader(value = "X-Requested-With", required = false) String xrw,
            HttpServletRequest http) {
        requireXrw(xrw);
        var result = authService.refresh(cookie);
        TokenResponse res = new TokenResponse(
                authService.issueAccessTokenFor(result.user()),
                TokenResponse.UserDto.of(result.user()));
        return ResponseEntity.ok()
                .header("Set-Cookie", refreshCookie(result.user().getId(), http, result.newRefreshToken()).toString())
                .body(res);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(value = REFRESH_COOKIE, required = false) String cookie,
            @RequestHeader(value = "X-Requested-With", required = false) String xrw,
            HttpServletRequest http) {
        requireXrw(xrw);
        AuthPrincipal actor = null;
        try {
            actor = SecurityUtils.current();
        } catch (ApiException ignored) {
            // logging out with an expired access token should still work
        }
        authService.logout(cookie, actor);
        return ResponseEntity.ok()
                .header("Set-Cookie", refreshCookie(null, http, null).toString())
                .build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        authService.forgotPassword(req.email());
        return ResponseEntity.ok(Map.of("message",
                "If that email exists, a reset link is on its way. Check your inbox (and spam folder)."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req.token(), req.newPassword());
        return ResponseEntity.ok(Map.of("message", "Password updated. You can sign in with your new password now."));
    }

    // ---------- /api/me ----------

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        AuthPrincipal p = SecurityUtils.current();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", p.userId());
        user.put("email", p.email());
        user.put("role", p.role().name());
        user.put("employeeId", p.employeeId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", user);
        companyRepo.findById(p.companyId()).ifPresent(c -> {
            Map<String, Object> company = new LinkedHashMap<>();
            company.put("id", c.getId());
            company.put("name", c.getName());
            company.put("currency", c.getCurrency());
            company.put("locale", c.getLocale());
            company.put("salaryPeriod", c.getSalaryPeriod());
            company.put("roundingRule", c.getRoundingRule());
            company.put("hrCanSeeSalary", c.isHrCanSeeSalary());
            out.put("company", company);
        });
        if (p.employeeId() != null) {
            employeeRepo.findByIdAndCompanyId(p.employeeId(), p.companyId()).ifPresent(e -> {
                Map<String, Object> employee = new LinkedHashMap<>();
                employee.put("id", e.getId());
                employee.put("firstName", e.getFirstName());
                employee.put("lastName", e.getLastName());
                out.put("employee", employee);
            });
        }
        return ResponseEntity.ok(out);
    }

    @org.springframework.web.bind.annotation.PutMapping("/me/password")
    public ResponseEntity<Map<String, String>> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        AuthPrincipal p = SecurityUtils.current();
        authService.changePassword(p, req.currentPassword(), req.newPassword());
        return ResponseEntity.ok(Map.of("message", "Password updated. Please sign in again with your new password."));
    }

    // ---------- helpers ----------

    private static void requireXrw(String xrw) {
        if (!"XMLHttpRequest".equals(xrw)) {
            throw new ApiException("Session expired. Please sign in again.", 401);
        }
    }

    private ResponseCookie refreshCookie(Long userId, HttpServletRequest http, String newToken) {
        boolean secure = http.isSecure();
        // path "/" (not /api/auth): the frontend middleware must see this cookie to guard
        // app routes. Still httpOnly + SameSite=Strict (+ Secure on HTTPS), rotated and
        // revoked server-side, so the wider path doesn't weaken it.
        if (newToken == null) {
            return ResponseCookie.from(REFRESH_COOKIE, "")
                    .httpOnly(true).secure(secure).sameSite("Strict")
                    .path("/").maxAge(0).build();
        }
        return ResponseCookie.from(REFRESH_COOKIE, newToken)
                .httpOnly(true).secure(secure).sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofDays(props.getJwt().getRefreshTtlDays()).toSeconds())
                .build();
    }

    private static String clientIp(HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        return fwd != null ? fwd.split(",")[0].trim() : http.getRemoteAddr();
    }
}

package com.empmg.platform.auth.service;

import com.empmg.platform.audit.service.AuditService;
import com.empmg.platform.auth.domain.PasswordResetToken;
import com.empmg.platform.auth.domain.RefreshToken;
import com.empmg.platform.auth.dto.LoginRequest;
import com.empmg.platform.auth.dto.OnboardingRequest;
import com.empmg.platform.auth.dto.TokenResponse;
import com.empmg.platform.auth.repo.PasswordResetTokenRepo;
import com.empmg.platform.auth.repo.RefreshTokenRepo;
import com.empmg.platform.common.AppProperties;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.common.PasswordPolicy;
import com.empmg.platform.company.domain.Company;
import com.empmg.platform.company.repo.CompanyRepo;
import com.empmg.platform.employee.repo.EmployeeRepo;
import com.empmg.platform.security.AccessRole;
import com.empmg.platform.security.AuthPrincipal;
import com.empmg.platform.security.JwtService;
import com.empmg.platform.user.domain.User;
import com.empmg.platform.user.repo.UserRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CompanyRepo companyRepo;
    private final UserRepo userRepo;
    private final RefreshTokenRepo refreshTokenRepo;
    private final PasswordResetTokenRepo resetTokenRepo;
    private final EmployeeRepo employeeRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties props;
    private final LoginRateLimiter rateLimiter;
    private final MailService mailService;
    private final AuditService audit;

    // ---------- onboarding ----------

    @Transactional
    public AuthResult onboarding(OnboardingRequest req) {
        if (userRepo.existsByEmailIgnoreCase(req.email())) {
            throw new ApiException("That email is already registered. Try signing in instead.");
        }
        checkPassword(req.password(), req.email());

        Company company = companyRepo.save(Company.builder()
                .name(req.companyName().trim())
                .currency("USD")
                .locale("en-US")
                .salaryPeriod("MONTHLY")
                .roundingRule("NONE")
                .hrCanSeeSalary(false)
                .createdAt(Instant.now())
                .build());

        User user = userRepo.save(User.builder()
                .companyId(company.getId())
                .email(req.email().trim().toLowerCase())
                .passwordHash(passwordEncoder.encode(req.password()))
                .accessRole(AccessRole.ADMIN)
                .status("ACTIVE")
                .failedAttempts(0)
                .createdAt(Instant.now())
                .build());

        AuthPrincipal principal = toPrincipal(user);
        audit.record(principal, company.getId(), "ONBOARDING", "COMPANY", company.getId(),
                Map.of("companyName", company.getName()));

        String refresh = issueRefresh(user.getId());
        return new AuthResult(new TokenResponse(jwtService.issueAccess(principal), TokenResponse.UserDto.of(user)), refresh);
    }

    /** Result of login/onboarding: the response body plus the raw refresh token for the httpOnly cookie. */
    public record AuthResult(TokenResponse response, String refreshToken) {}

    // ---------- login / refresh / logout ----------

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResult login(LoginRequest req, String ip) {
        String email = req.email().trim().toLowerCase();
        if (rateLimiter.isBlocked(email, ip)) {
            throw new ApiException(
                    "Too many failed attempts. Please wait 15 minutes and try again.", 429);
        }
        var userOpt = userRepo.findByEmailIgnoreCase(email);
        AuthPrincipal actor = userOpt.map(this::toPrincipal).orElse(null);

        if (userOpt.isEmpty() || !passwordEncoder.matches(req.password(), userOpt.get().getPasswordHash())) {
            rateLimiter.recordFailure(email, ip);
            userOpt.ifPresent(u -> registerPersistentFailure(u));
            audit.record(actor, userOpt.map(User::getCompanyId).orElse(null), "LOGIN_FAILED", "USER", null,
                    Map.of("email", email));
            // generic message — never reveal whether the account exists
            throw new ApiException("Email or password is incorrect.", 401);
        }

        User user = userOpt.get();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            throw new ApiException(
                    "This account is temporarily locked after too many failed attempts. Try again in a few minutes.", 423);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ApiException("This account is deactivated. Ask your administrator to reactivate it.", 403);
        }

        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepo.save(user);
        rateLimiter.clear(email, ip);

        AuthPrincipal principal = toPrincipal(user);
        audit.record(principal, user.getCompanyId(), "LOGIN", "USER", user.getId(), Map.of());
        String refresh = issueRefresh(user.getId());
        return new AuthResult(new TokenResponse(jwtService.issueAccess(principal), TokenResponse.UserDto.of(user)), refresh);
    }

    /** Validates a refresh token from the cookie and rotates it. Returns the user + a new refresh token. */
    public record RefreshResult(User user, String newRefreshToken) {}
    @Transactional
    public RefreshResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException("Session expired. Please sign in again.", 401);
        }
        RefreshToken stored = refreshTokenRepo.findByTokenHash(sha256(refreshToken))
                .orElseThrow(() -> new ApiException("Session expired. Please sign in again.", 401));
        Instant now = Instant.now();
        // A refresh token is single-use. The database lock serializes concurrent rotation.
        if (!stored.getExpiresAt().isAfter(now) || stored.getRevokedAt() != null) {
            throw new ApiException("Session expired. Please sign in again.", 401);
        }
        stored.setRevokedAt(now);
        refreshTokenRepo.save(stored);

        User user = userRepo.findById(stored.getUserId())
                .orElseThrow(() -> new ApiException("Session expired. Please sign in again.", 401));
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ApiException("This account is deactivated.", 403);
        }
        return new RefreshResult(user, issueRefresh(user.getId()));
    }

    @Transactional
    public void logout(String refreshToken, AuthPrincipal actor) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenRepo.findByTokenHash(sha256(refreshToken)).ifPresent(t -> {
                t.setRevokedAt(Instant.now());
                refreshTokenRepo.save(t);
            });
        }
        if (actor != null) {
            audit.record(actor, actor.companyId(), "LOGOUT", "USER", actor.userId(), Map.of());
        }
    }

    // ---------- password reset ----------

    @Transactional
    public void forgotPassword(String email) {
        var userOpt = userRepo.findByEmailIgnoreCase(email.trim().toLowerCase());
        if (userOpt.isEmpty()) {
            return; // no enumeration: identical response either way
        }
        User user = userOpt.get();
        String raw = randomToken();
        resetTokenRepo.save(PasswordResetToken.builder()
                .userId(user.getId())
                .tokenHash(sha256(raw))
                .expiresAt(Instant.now().plus(Duration.ofMinutes(30)))
                .createdAt(Instant.now())
                .build());
        String link = props.getFrontendUrl() + "/reset-password?token=" + raw;
        mailService.sendPasswordReset(user.getEmail(), link);
        audit.record(toPrincipal(user), user.getCompanyId(), "PASSWORD_RESET_REQUESTED", "USER", user.getId(), Map.of());
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken prt = resetTokenRepo.findByTokenHash(sha256(token))
                .orElseThrow(() -> new ApiException("This reset link is invalid or has expired. Request a new one."));
        if (prt.getUsedAt() != null || prt.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException("This reset link is invalid or has expired. Request a new one.");
        }
        User user = userRepo.findById(prt.getUserId())
                .orElseThrow(() -> new ApiException("This reset link is invalid or has expired. Request a new one."));
        checkPassword(newPassword, user.getEmail());
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setCredentialVersion(user.getCredentialVersion() + 1);
        user.setPasswordChangeRequired(false);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepo.save(user);
        prt.setUsedAt(Instant.now());
        resetTokenRepo.save(prt);
        refreshTokenRepo.revokeAllForUser(user.getId()); // force re-login everywhere
        audit.record(toPrincipal(user), user.getCompanyId(), "PASSWORD_RESET", "USER", user.getId(), Map.of());
    }

    @Transactional
    public void changePassword(AuthPrincipal actor, String currentPassword, String newPassword) {
        User user = userRepo.findById(actor.userId()).orElseThrow();
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException("Your current password is incorrect.");
        }
        checkPassword(newPassword, user.getEmail());
        if(passwordEncoder.matches(newPassword,user.getPasswordHash())) throw new ApiException("Choose a password different from your current one.");
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setCredentialVersion(user.getCredentialVersion() + 1);
        user.setPasswordChangeRequired(false);
        userRepo.save(user);
        refreshTokenRepo.revokeAllForUser(user.getId());
        audit.record(actor, actor.companyId(), "PASSWORD_CHANGED", "USER", user.getId(), Map.of());
    }

    // ---------- helpers ----------

    private void registerPersistentFailure(User u) {
        u.setFailedAttempts(u.getFailedAttempts() + 1);
        if (u.getFailedAttempts() >= props.getRateLimit().getLoginMaxFailures()) {
            u.setLockedUntil(Instant.now().plus(Duration.ofMinutes(props.getRateLimit().getLoginLockMinutes())));
        }
        userRepo.save(u);
    }

    private String issueRefresh(Long userId) {
        String raw = randomToken();
        refreshTokenRepo.save(RefreshToken.builder()
                .userId(userId)
                .tokenHash(sha256(raw))
                .expiresAt(Instant.now().plus(Duration.ofDays(props.getJwt().getRefreshTtlDays())))
                .createdAt(Instant.now())
                .build());
        return raw;
    }

    private AuthPrincipal toPrincipal(User u) {
        return new AuthPrincipal(u.getId(), u.getCompanyId(), u.getEmail(), u.getAccessRole(), u.getEmployeeId());
    }

    /** Issues an access token from a freshly loaded user (used after refresh-token rotation). */
    public String issueAccessTokenFor(User u) {
        return jwtService.issueAccess(toPrincipal(u));
    }

    private void checkPassword(String password, String email) {
        String error = PasswordPolicy.validate(password, email);
        if (error != null) {
            throw new ApiException(error);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

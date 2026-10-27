package com.empmg.platform.user;

import com.empmg.platform.audit.service.AuditService;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.common.PasswordPolicy;
import com.empmg.platform.employee.repo.EmployeeRepo;
import com.empmg.platform.security.AccessRole;
import com.empmg.platform.security.AuthPrincipal;
import com.empmg.platform.security.SecurityUtils;
import com.empmg.platform.user.domain.User;
import com.empmg.platform.user.repo.UserRepo;
import jakarta.validation.constraints.Email;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Account administration: create sign-ins for HR, managers and employees. Admin only. */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private static final Set<AccessRole> ASSIGNABLE =
            Set.of(AccessRole.ADMIN, AccessRole.CFO, AccessRole.HR, AccessRole.MANAGER, AccessRole.EMPLOYEE);

    private final UserRepo userRepo;
    private final EmployeeRepo employeeRepo;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    private final com.empmg.platform.auth.repo.RefreshTokenRepo refreshTokens;

    public record CreateUserRequest(@NotBlank @Email String email, String password,
                                    @NotBlank String role, Long employeeId) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','CFO')")
    public List<Map<String, Object>> list() {
        AuthPrincipal p = SecurityUtils.current();
        return userRepo.findByCompanyIdOrderById(p.companyId()).stream()
                .map(u -> Map.<String, Object>of(
                        "id", u.getId(),
                        "email", u.getEmail(),
                        "role", u.getAccessRole().name(),
                        "employeeId", u.getEmployeeId() == null ? "" : u.getEmployeeId(),
                        "status", u.getStatus()))
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','CFO')")
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody CreateUserRequest req) {
        AuthPrincipal p = SecurityUtils.current();
        String email = req.email().trim().toLowerCase();
        if (userRepo.existsByEmailIgnoreCase(email)) {
            throw new ApiException("That email already has an account. Use a different email.");
        }
        AccessRole role;
        try {
            role = AccessRole.valueOf(req.role());
        } catch (IllegalArgumentException e) {
            throw new ApiException("That role doesn't exist.");
        }
        if (!ASSIGNABLE.contains(role)) {
            throw new ApiException("The ADMIN role is created at setup and can't be assigned here.");
        }
        boolean generated=req.password()==null||req.password().isBlank();
        String password=generated?temporaryPassword():req.password();
        String pwError = PasswordPolicy.validate(password, email);
        if (pwError != null) throw new ApiException(pwError);
        if (req.employeeId() != null && employeeRepo.findByIdAndCompanyId(req.employeeId(), p.companyId()).isEmpty()) {
            throw new ApiException("That employee doesn't exist.");
        }
        if ((role == AccessRole.MANAGER || role == AccessRole.EMPLOYEE) && req.employeeId() == null) {
            throw new ApiException("Link this account to an employee so team and ownership access can work.");
        }

        User u = userRepo.save(User.builder()
                .companyId(p.companyId())
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .passwordChangeRequired(generated)
                .welcomeRequired(generated && req.employeeId() != null)
                .accessRole(role)
                .employeeId(req.employeeId())
                .status("ACTIVE")
                .failedAttempts(0)
                .createdAt(Instant.now())
                .build());
        audit.record(p, p.companyId(), "USER_CREATED", "USER", u.getId(),
                Map.of("email", email, "role", role.name()));
        Map<String,Object> result=new java.util.LinkedHashMap<>(Map.of("id", u.getId(), "email", u.getEmail(), "role", u.getAccessRole().name()));
        if(generated) result.put("temporaryPassword",password);
        return result;
    }

    public record UpdateUserRequest(@NotBlank String role, @NotBlank String status, Long employeeId) {}

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CFO')")
    @Transactional
    public Object update(@PathVariable Long id,@Valid @RequestBody UpdateUserRequest req) {
        var p=SecurityUtils.current();
        db.queryForObject("select id from companies where id=? for update",Long.class,p.companyId());
        var user=userRepo.findById(id).filter(u->u.getCompanyId().equals(p.companyId())).orElseThrow(()->new ApiException("Account not found.",404));
        AccessRole role;
        try { role=AccessRole.valueOf(req.role()); } catch(Exception e) { throw new ApiException("Choose a valid role."); }
        if(!Set.of("ACTIVE","INACTIVE").contains(req.status())) throw new ApiException("Choose active or inactive.");
        if(user.getId().equals(p.userId()) && (!req.status().equals("ACTIVE") || role!=p.role())) throw new ApiException("Ask another Admin or CFO to change your own role or deactivate you.");
        if(user.getAccessRole()==AccessRole.ADMIN && (!req.status().equals("ACTIVE")||role!=AccessRole.ADMIN)
            &&userRepo.findByCompanyIdOrderById(p.companyId()).stream().filter(u->u.getAccessRole()==AccessRole.ADMIN&&u.getStatus().equals("ACTIVE")).count()<=1)
            throw new ApiException("Keep at least one active Admin.");
        if(req.employeeId()!=null&&employeeRepo.findByIdAndCompanyId(req.employeeId(),p.companyId()).isEmpty()) throw new ApiException("Employee not found.");
        if((role==AccessRole.MANAGER||role==AccessRole.EMPLOYEE)&&req.employeeId()==null) throw new ApiException("Link this account to an employee.");
        user.setAccessRole(role); user.setStatus(req.status()); user.setEmployeeId(req.employeeId()); userRepo.save(user);
        refreshTokens.revokeAllForUser(id);
        audit.record(p,p.companyId(),"USER_UPDATED","USER",id,Map.of("role",role.name(),"status",req.status()));
        return Map.of("id",id,"role",role.name(),"status",req.status());
    }
    @PostMapping("/{id}/credentials")
    @PreAuthorize("hasAnyRole('ADMIN','CFO')")
    @Transactional
    public Object credentials(@PathVariable Long id) {
        var p=SecurityUtils.current();
        if(p.userId().equals(id)) throw new ApiException("Use Change password for your own account.");
        var user=userRepo.findById(id).filter(u->u.getCompanyId().equals(p.companyId())).orElseThrow(()->new ApiException("Account not found.",404));
        String password=temporaryPassword();
        user.setPasswordHash(passwordEncoder.encode(password)); user.setPasswordChangeRequired(true);
        // Regenerating a password must not erase a completed employee profile.
        if(user.isWelcomeRequired()) db.update("update users set email_verified_at=null,otp_expires_at=null,otp_requested_at=null,otp_attempts=0 where id=?",id);
        user.setCredentialVersion(user.getCredentialVersion() + 1);
        user.setFailedAttempts(0); user.setLockedUntil(null); userRepo.save(user); refreshTokens.revokeAllForUser(id);
        audit.record(p,p.companyId(),"USER_CREDENTIALS_GENERATED","USER",id,Map.of());
        return Map.of("email",user.getEmail(),"temporaryPassword",password);
    }
    private String temporaryPassword() {
        byte[] bytes=new byte[18]; new java.security.SecureRandom().nextBytes(bytes);
        return "Aa1!"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

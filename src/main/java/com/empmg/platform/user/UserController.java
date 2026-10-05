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
            Set.of(AccessRole.CFO, AccessRole.HR, AccessRole.MANAGER, AccessRole.EMPLOYEE);

    private final UserRepo userRepo;
    private final EmployeeRepo employeeRepo;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public record CreateUserRequest(@NotBlank @Email String email, @NotBlank String password,
                                    @NotBlank String role, Long employeeId) {}

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
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
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Map<String, Object> create(@RequestBody CreateUserRequest req) {
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
        String pwError = PasswordPolicy.validate(req.password(), email);
        if (pwError != null) throw new ApiException(pwError);
        if (req.employeeId() != null && employeeRepo.findByIdAndCompanyId(req.employeeId(), p.companyId()).isEmpty()) {
            throw new ApiException("That employee doesn't exist.");
        }

        User u = userRepo.save(User.builder()
                .companyId(p.companyId())
                .email(email)
                .passwordHash(passwordEncoder.encode(req.password()))
                .accessRole(role)
                .employeeId(req.employeeId())
                .status("ACTIVE")
                .failedAttempts(0)
                .createdAt(Instant.now())
                .build());
        audit.record(p, p.companyId(), "USER_CREATED", "USER", u.getId(),
                Map.of("email", email, "role", role.name()));
        return Map.of("id", u.getId(), "email", u.getEmail(), "role", u.getAccessRole().name());
    }
}

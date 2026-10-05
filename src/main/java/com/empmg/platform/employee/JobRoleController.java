package com.empmg.platform.employee;

import com.empmg.platform.audit.service.AuditService;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.employee.domain.JobRole;
import com.empmg.platform.employee.repo.JobRoleRepo;
import com.empmg.platform.security.AuthPrincipal;
import com.empmg.platform.security.SecurityUtils;
import com.empmg.platform.tier.repo.TierRepo;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Job roles: what people do, with optional defaults for tier, salary range and variable pay. */
@RestController
@RequestMapping("/api/job-roles")
@RequiredArgsConstructor
public class JobRoleController {

    private final JobRoleRepo jobRoleRepo;
    private final TierRepo tierRepo;
    private final AuditService audit;

    public record RoleDto(Long id, String name, Long defaultTierId, String defaultTierName,
                          BigDecimal minSalary, BigDecimal maxSalary, String defaultComponents, boolean archived) {
        static RoleDto of(JobRole r, String tierName) {
            return new RoleDto(r.getId(), r.getName(), r.getDefaultTierId(), tierName,
                    r.getMinSalary(), r.getMaxSalary(), r.getDefaultComponents(), r.isArchived());
        }
    }

    public record RoleInput(@NotBlank String name, Long defaultTierId, BigDecimal minSalary,
                            BigDecimal maxSalary, String defaultComponents) {}

    @GetMapping
    public List<RoleDto> list() {
        AuthPrincipal p = SecurityUtils.current();
        Map<Long, String> tierNames = new java.util.HashMap<>();
        tierRepo.findByCompanyIdOrderBySortOrderAscIdAsc(p.companyId()).forEach(t -> tierNames.put(t.getId(), t.getName()));
        return jobRoleRepo.findByCompanyIdAndArchivedFalseOrderByNameAsc(p.companyId()).stream()
                .map(r -> RoleDto.of(r, tierNames.get(r.getDefaultTierId())))
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','CFO','HR')")
    @Transactional
    public RoleDto create(@RequestBody RoleInput in) {
        AuthPrincipal p = SecurityUtils.current();
        validate(p, in, null);
        JobRole r = jobRoleRepo.save(JobRole.builder()
                .companyId(p.companyId())
                .name(in.name().trim())
                .defaultTierId(in.defaultTierId())
                .minSalary(in.minSalary())
                .maxSalary(in.maxSalary())
                .defaultComponents(in.defaultComponents())
                .archived(false)
                .createdAt(Instant.now())
                .build());
        audit.record(p, p.companyId(), "JOB_ROLE_CREATED", "JOB_ROLE", r.getId(), Map.of("name", r.getName()));
        return RoleDto.of(r, tierName(p, r.getDefaultTierId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CFO','HR')")
    @Transactional
    public RoleDto update(@PathVariable Long id, @RequestBody RoleInput in) {
        AuthPrincipal p = SecurityUtils.current();
        validate(p, in, id);
        JobRole r = jobRoleRepo.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> new ApiException("Job role not found.", 404));
        r.setName(in.name().trim());
        r.setDefaultTierId(in.defaultTierId());
        r.setMinSalary(in.minSalary());
        r.setMaxSalary(in.maxSalary());
        r.setDefaultComponents(in.defaultComponents());
        jobRoleRepo.save(r);
        audit.record(p, p.companyId(), "JOB_ROLE_UPDATED", "JOB_ROLE", r.getId(), Map.of("name", r.getName()));
        return RoleDto.of(r, tierName(p, r.getDefaultTierId()));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('ADMIN','CFO','HR')")
    @Transactional
    public Map<String, Object> archive(@PathVariable Long id) {
        AuthPrincipal p = SecurityUtils.current();
        JobRole r = jobRoleRepo.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> new ApiException("Job role not found.", 404));
        r.setArchived(!r.isArchived());
        jobRoleRepo.save(r);
        audit.record(p, p.companyId(), r.isArchived() ? "JOB_ROLE_ARCHIVED" : "JOB_ROLE_RESTORED",
                "JOB_ROLE", r.getId(), Map.of("name", r.getName()));
        return Map.of("archived", r.isArchived());
    }

    private void validate(AuthPrincipal p, RoleInput in, Long selfId) {
        jobRoleRepo.findByCompanyIdAndNameIgnoreCaseAndArchivedFalse(p.companyId(), in.name().trim())
                .ifPresent(other -> {
                    if (!other.getId().equals(selfId)) {
                        throw new ApiException("A job role with that name already exists.");
                    }
                });
        if (in.defaultTierId() != null && tierRepo.findByIdAndCompanyId(in.defaultTierId(), p.companyId()).isEmpty()) {
            throw new ApiException("The default tier doesn't exist.");
        }
        if (in.minSalary() != null && in.maxSalary() != null && in.minSalary().compareTo(in.maxSalary()) > 0) {
            throw new ApiException("The salary minimum can't be higher than the maximum.");
        }
    }

    private String tierName(AuthPrincipal p, Long tierId) {
        return tierId == null ? null : tierRepo.findByIdAndCompanyId(tierId, p.companyId())
                .map(t -> t.getName()).orElse(null);
    }
}

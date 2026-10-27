package com.empmg.platform.audit;

import com.empmg.platform.audit.repo.AuditLogRepo;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Append-only audit trail — Admin only. */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepo repo;

    public record Entry(Long id, String action, String actor, String entityType, String entityId,
                        String detail, String ip, Instant at) {}

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String action,
                                    @RequestParam(required = false) Instant from,
                                    @RequestParam(required = false) Instant to,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        if (!java.util.Set.of("ADMIN","HR").contains(SecurityUtils.current().role().name())) {
            throw new ApiException("Only the administrator can view the audit log.", 403);
        }
        Page<com.empmg.platform.audit.domain.AuditLog> result = repo.search(
                SecurityUtils.current().companyId(),
                action == null || action.isBlank() ? null : action,
                from, to,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalElements", result.getTotalElements());
        out.put("totalPages", result.getTotalPages());
        out.put("page", result.getNumber());
        out.put("content", result.getContent().stream()
                .map(a -> new Entry(a.getId(), a.getAction(), a.getActorEmail(), a.getEntityType(),
                        a.getEntityId(), a.getDetail(), a.getIpAddress(), a.getCreatedAt()))
                .toList());
        return out;
    }
}

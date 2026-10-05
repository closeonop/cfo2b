package com.empmg.platform.audit.service;

import com.empmg.platform.audit.domain.AuditLog;
import com.empmg.platform.audit.repo.AuditLogRepo;
import com.empmg.platform.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Map;

/**
 * Append-only audit trail for every view/create/update/delete/import/export of
 * compensation or personnel data, and for auth events.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepo repo;

    /** REQUIRES_NEW: audit writes must land even from read-only transactions, and never roll back with them. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuthPrincipal actor, Long companyId, String action, String entityType,
                       Object entityId, Map<String, Object> detail) {
        if (companyId == null) {
            // audit rows are company-scoped; nothing to attach (e.g. failed login for an unknown email)
            return;
        }
        try {
            String ip = null;
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
                HttpServletRequest req = sra.getRequest();
                String fwd = req.getHeader("X-Forwarded-For");
                ip = fwd != null ? fwd.split(",")[0].trim() : req.getRemoteAddr();
            }
            repo.save(AuditLog.builder()
                    .companyId(companyId)
                    .actorUserId(actor != null ? actor.userId() : null)
                    .actorEmail(actor != null ? actor.email() : null)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId != null ? String.valueOf(entityId) : null)
                    .detail(detail != null ? Json.toJson(detail) : null)
                    .ipAddress(ip)
                    .createdAt(Instant.now())
                    .build());
        } catch (Exception e) {
            // auditing must never break the business action, but the failure is loud in logs
            log.error("AUDIT WRITE FAILED action={} entity={}/{}", action, entityType, entityId, e);
        }
    }

    /** Json helper kept here to avoid a dependency on a JSON library in entities. */
    public static final class Json {
        private Json() {}

        public static String toJson(Map<String, Object> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(e.getKey())).append(':').append(value(e.getValue()));
            }
            return sb.append('}').toString();
        }

        private static String value(Object v) {
            if (v == null) return "null";
            if (v instanceof Number || v instanceof Boolean) return v.toString();
            return quote(String.valueOf(v));
        }

        private static String quote(String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            return sb.append('"').toString();
        }
    }
}

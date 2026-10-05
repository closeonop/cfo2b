package com.empmg.platform.audit.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import lombok.*;

import java.time.Instant;

/** Append-only audit trail. Who did what, when, from where. Never updated or deleted. */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(name = "entity_type", length = 60)
    private String entityType;

    @Column(name = "entity_id", length = 60)
    private String entityId;

    @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detail;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

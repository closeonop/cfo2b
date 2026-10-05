package com.empmg.platform.audit.repo;

import com.empmg.platform.audit.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AuditLogRepo extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByCompanyIdOrderByCreatedAtDesc(Long companyId, Pageable pageable);

    @Query("""
            select a from AuditLog a
            where a.companyId = :companyId
              and (cast(:action as string) is null or a.action = :action)
              and (cast(:from as timestamp) is null or a.createdAt >= :from)
              and (cast(:to as timestamp) is null or a.createdAt <= :to)
            """)
    Page<AuditLog> search(@Param("companyId") Long companyId,
                          @Param("action") String action,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable pageable);
}

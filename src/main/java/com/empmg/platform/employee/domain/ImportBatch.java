package com.empmg.platform.employee.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import lombok.*;

import java.time.Instant;

/** One CSV import. Holds the snapshot needed to undo it. */
@Entity
@Table(name = "import_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "created_count", nullable = false)
    private int createdCount;

    @Column(name = "updated_count", nullable = false)
    private int updatedCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    @Column(name = "error_count", nullable = false)
    private int errorCount;

    /** JSON snapshot for undo: [{action: CREATED|UPDATED, id, before:{...}}] */
    @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "undo_data", columnDefinition = "jsonb")
    private String undoData;

    @Column(name = "undone_at")
    private Instant undoneAt;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

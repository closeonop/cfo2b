package com.empmg.platform.employee.repo;

import com.empmg.platform.employee.domain.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ImportBatchRepo extends JpaRepository<ImportBatch, Long> {

    Optional<ImportBatch> findByIdAndCompanyId(Long id, Long companyId);

    Optional<ImportBatch> findTopByCompanyIdAndUndoneAtIsNullOrderByCreatedAtDesc(Long companyId);

    List<ImportBatch> findTop10ByCompanyIdOrderByCreatedAtDesc(Long companyId);
}

package com.empmg.platform.tier.repo;

import com.empmg.platform.tier.domain.Tier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TierRepo extends JpaRepository<Tier, Long> {

    List<Tier> findByCompanyIdOrderBySortOrderAscIdAsc(Long companyId);

    List<Tier> findByCompanyIdAndArchivedFalseOrderBySortOrderAscIdAsc(Long companyId);

    Optional<Tier> findByIdAndCompanyId(Long id, Long companyId);

    boolean existsByCompanyIdAndNameIgnoreCaseAndArchivedFalse(Long companyId, String name);

    Optional<Tier> findByCompanyIdAndNameIgnoreCase(Long companyId, String name);
}

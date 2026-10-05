package com.empmg.platform.compensation.repo;

import com.empmg.platform.compensation.domain.Raise;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.LocalDate;

public interface RaiseRepo extends JpaRepository<Raise, Long> {

    Optional<Raise> findByIdAndCompanyId(Long id, Long companyId);

    List<Raise> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

    List<Raise> findByCompanyIdAndStatusInOrderByCreatedAtDesc(Long companyId, Collection<String> statuses);

    /** Approved raises whose effective date has arrived — picked up by the scheduler. */
    List<Raise> findByCompanyIdAndStatus(Long companyId, String status);

    List<Raise> findByStatusAndEffectiveDateLessThanEqual(String status, LocalDate date);
}

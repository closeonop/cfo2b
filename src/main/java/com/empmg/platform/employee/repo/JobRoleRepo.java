package com.empmg.platform.employee.repo;

import com.empmg.platform.employee.domain.JobRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JobRoleRepo extends JpaRepository<JobRole, Long> {

    List<JobRole> findByCompanyIdAndArchivedFalseOrderByNameAsc(Long companyId);

    Optional<JobRole> findByIdAndCompanyId(Long id, Long companyId);

    Optional<JobRole> findByCompanyIdAndNameIgnoreCaseAndArchivedFalse(Long companyId, String name);
}

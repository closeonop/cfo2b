package com.empmg.platform.compensation.repo;

import com.empmg.platform.compensation.domain.SalaryHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SalaryHistoryRepo extends JpaRepository<SalaryHistory, Long> {

    List<SalaryHistory> findByCompanyIdAndEmployeeIdOrderByEffectiveDateDescIdDesc(Long companyId, Long employeeId);

    List<SalaryHistory> findByCompanyIdOrderByCreatedAtDesc(Long companyId);
}

package com.empmg.platform.compensation.repo;

import com.empmg.platform.compensation.domain.VariableComponent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VariableComponentRepo extends JpaRepository<VariableComponent, Long> {

    List<VariableComponent> findByCompanyIdAndEmployeeIdOrderByIdAsc(Long companyId, Long employeeId);

    List<VariableComponent> findByCompanyId(Long companyId);

    List<VariableComponent> findByCompanyIdAndEmployeeIdAndActiveTrue(Long companyId, Long employeeId);

    Optional<VariableComponent> findByIdAndCompanyId(Long id, Long companyId);
}

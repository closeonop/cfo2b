package com.empmg.platform.employee.repo;

import com.empmg.platform.employee.domain.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepo extends JpaRepository<Employee, Long> {

    Optional<Employee> findByIdAndCompanyId(Long id, Long companyId);

    List<Employee> findByCompanyIdOrderByFirstNameAscLastNameAsc(Long companyId);

    List<Employee> findByCompanyIdAndManagerId(Long companyId, Long managerId);

    long countByCompanyId(Long companyId);

    Optional<Employee> findByCompanyIdAndEmailIgnoreCase(Long companyId, String email);

    Optional<Employee> findByCompanyIdAndEmployeeCodeIgnoreCase(Long companyId, String employeeCode);

    boolean existsByCompanyIdAndEmailIgnoreCase(Long companyId, String email);

    /** Distinct team names for filters. */
    @Query("select distinct e.team from Employee e where e.companyId = :companyId and e.team is not null order by e.team")
    List<String> findDistinctTeams(@Param("companyId") Long companyId);

    List<Employee> findByCompanyIdAndTierId(Long companyId, Long tierId);
}

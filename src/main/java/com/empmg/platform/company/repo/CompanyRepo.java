package com.empmg.platform.company.repo;

import com.empmg.platform.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepo extends JpaRepository<Company, Long> {
}

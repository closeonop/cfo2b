package com.empmg.platform.user.repo;

import com.empmg.platform.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepo extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<User> findByCompanyIdOrderById(Long companyId);

    Optional<User> findByIdAndCompanyId(Long id, Long companyId);
}

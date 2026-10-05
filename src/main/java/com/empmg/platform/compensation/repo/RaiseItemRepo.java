package com.empmg.platform.compensation.repo;

import com.empmg.platform.compensation.domain.RaiseItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RaiseItemRepo extends JpaRepository<RaiseItem, Long> {

    List<RaiseItem> findByRaiseId(Long raiseId);
}

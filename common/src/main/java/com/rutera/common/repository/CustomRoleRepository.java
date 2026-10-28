package com.rutera.common.repository;

import com.rutera.common.domain.CustomRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface CustomRoleRepository extends JpaRepository<CustomRole, Long> {

    List<CustomRole> findAllByOrderByNameAsc();
}

package com.rutera.common.repository;

import com.rutera.common.domain.UserPrivilege;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Tenant-filtered. */
public interface UserPrivilegeRepository extends JpaRepository<UserPrivilege, Long> {

    List<UserPrivilege> findByUserId(Long userId);
}

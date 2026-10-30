package com.rutera.common.repository;

import com.rutera.common.domain.Driver;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface DriverRepository extends JpaRepository<Driver, Long> {

    List<Driver> findByDeletedFalseOrderByLastNameAscFirstNameAsc();

    Optional<Driver> findByIdAndDeletedFalse(Long id);

    List<Driver> findByIdInAndDeletedFalse(Iterable<Long> ids);
}

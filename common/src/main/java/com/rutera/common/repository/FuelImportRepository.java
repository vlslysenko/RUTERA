package com.rutera.common.repository;

import com.rutera.common.domain.FuelImport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface FuelImportRepository extends JpaRepository<FuelImport, Long> {

    Page<FuelImport> findAllByOrderByIdDesc(Pageable pageable);
}

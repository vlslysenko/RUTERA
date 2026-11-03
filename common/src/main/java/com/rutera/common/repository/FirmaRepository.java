package com.rutera.common.repository;

import com.rutera.common.domain.Firma;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FirmaRepository extends JpaRepository<Firma, Long> {

    Optional<Firma> findByShortCode(String shortCode);

    /** {@code q}: lower-case "%text%" over name and short code, or null for all. */
    @Query("select f from Firma f where :q is null or lower(f.name) like :q or lower(f.shortCode) like :q")
    Page<Firma> search(@Param("q") String q, Pageable pageable);
}

package com.rutera.common.repository;

import com.rutera.common.domain.ApiCall;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface ApiCallRepository extends JpaRepository<ApiCall, Long> {

    Page<ApiCall> findByTokenIdOrderByIdDesc(Long tokenId, Pageable pageable);
}

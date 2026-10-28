package com.rutera.common.repository;

import com.rutera.common.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Not tenant-filtered: callers pass the firma explicitly. */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByFirmaIdOrderByAtDesc(Long firmaId, Pageable pageable);
}

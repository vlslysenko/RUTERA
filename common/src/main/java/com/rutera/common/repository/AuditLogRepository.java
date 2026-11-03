package com.rutera.common.repository;

import com.rutera.common.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/** Not tenant-filtered: callers pass the firma explicitly. */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByFirmaIdOrderByAtDesc(Long firmaId, Pageable pageable);

    /**
     * The journal newest first; every filter is optional (null). {@code firmaId} null = all firmas (operator only).
     * {@code q}: lower-case "%text%" over summary, action and entity.
     */
    @Query(value = """
            select a from AuditLog a
            where (:firmaId is null or a.firmaId = :firmaId)
              and (:from is null or a.at >= :from) and (:to is null or a.at < :to)
              and (:userId is null or a.userId = :userId)
              and (:module is null or a.module = :module)
              and (:channel is null or a.channel = :channel)
              and (:q is null or lower(a.summary) like :q or lower(a.action) like :q or lower(a.entity) like :q)
            order by a.at desc, a.id desc""",
            countQuery = """
            select count(a) from AuditLog a
            where (:firmaId is null or a.firmaId = :firmaId)
              and (:from is null or a.at >= :from) and (:to is null or a.at < :to)
              and (:userId is null or a.userId = :userId)
              and (:module is null or a.module = :module)
              and (:channel is null or a.channel = :channel)
              and (:q is null or lower(a.summary) like :q or lower(a.action) like :q or lower(a.entity) like :q)""")
    Page<AuditLog> search(@Param("firmaId") Long firmaId, @Param("from") Instant from, @Param("to") Instant to,
                          @Param("userId") Long userId, @Param("module") String module,
                          @Param("channel") AuditLog.Channel channel, @Param("q") String q, Pageable pageable);
}

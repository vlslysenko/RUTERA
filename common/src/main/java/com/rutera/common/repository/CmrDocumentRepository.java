package com.rutera.common.repository;

import com.rutera.common.domain.CmrDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface CmrDocumentRepository extends JpaRepository<CmrDocument, Long> {

    /**
     * The CMR list: every filter is optional (null = any); {@code from}/{@code to} bound the loading date, {@code q} is
     * a lower-case LIKE pattern on the number, the parties and the places. Newest first.
     */
    @Query("""
            select c from CmrDocument c
            where (:status is null or c.status = :status)
              and (:orderId is null or c.orderId = :orderId)
              and (:tripId is null or c.tripId = :tripId)
              and (:from is null or c.loadingDate >= :from)
              and (:to is null or c.loadingDate <= :to)
              and (:q is null or lower(c.number) like :q or lower(cast(c.sender as string)) like :q
                   or lower(cast(c.consignee as string)) like :q or lower(c.loadingPlace) like :q
                   or lower(c.deliveryPlace) like :q)
            order by c.id desc""")
    List<CmrDocument> search(@Param("status") CmrDocument.Status status, @Param("orderId") Long orderId,
                             @Param("tripId") Long tripId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                             @Param("q") String q);

    Optional<CmrDocument> findByNumber(String number);

    @Query("select c.number from CmrDocument c where c.number like :prefix")
    List<String> numbersLike(@Param("prefix") String prefix);

    /** A deleted trip leaves its CMR notes as documents without the link. */
    @Modifying
    @Query("update CmrDocument c set c.tripId = null where c.tripId = :tripId")
    int detachTrip(@Param("tripId") Long tripId);

    /** A deleted order leaves its CMR notes as documents without the link. */
    @Modifying
    @Query("update CmrDocument c set c.orderId = null where c.orderId = :orderId")
    int detachOrder(@Param("orderId") Long orderId);
}

package com.rutera.common.repository;

import com.rutera.common.domain.TransportOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface TransportOrderRepository extends JpaRepository<TransportOrder, Long> {

    /**
     * The order list: every filter is optional (null = any); {@code q} is a lower-case LIKE pattern matched against
     * the number, the customer's reference and the customer's name. Newest orders first.
     */
    @Query("""
            select o from TransportOrder o
            where (:status is null or o.status = :status)
              and (:contractorId is null or o.contractorId = :contractorId)
              and (:from is null or o.orderDate >= :from)
              and (:to is null or o.orderDate <= :to)
              and (:q is null or lower(o.number) like :q or lower(o.customerRef) like :q
                   or exists (select c.id from Contractor c where c.id = o.contractorId and lower(c.name) like :q))
            order by o.orderDate desc, o.id desc""")
    Page<TransportOrder> search(@Param("status") TransportOrder.Status status,
                                @Param("contractorId") Long contractorId, @Param("from") LocalDate from,
                                @Param("to") LocalDate to, @Param("q") String q, Pageable pageable);

    List<TransportOrder> findByTripIdOrderByNumberAsc(Long tripId);

    List<TransportOrder> findByTripIdIn(Collection<Long> tripIds);

    Optional<TransportOrder> findByNumber(String number);

    @Query("select o.number from TransportOrder o where o.number like :prefix")
    List<String> numbersLike(@Param("prefix") String prefix);
}

package com.rutera.common.repository;

import com.rutera.common.domain.FuelProvider;
import com.rutera.common.domain.FuelTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface FuelTransactionRepository extends JpaRepository<FuelTransaction, Long> {

    /**
     * The transaction list: every filter is optional (null = any); {@code unmatched} keeps rows without a vehicle;
     * {@code q} is a lower-case LIKE pattern on the station, the raw product and the raw plate. Newest first.
     */
    @Query("""
            select t from FuelTransaction t
            where (:from is null or t.at >= :from)
              and (:to is null or t.at < :to)
              and (:vehicleId is null or t.vehicleId = :vehicleId)
              and (:driverId is null or t.driverId = :driverId)
              and (:cardId is null or t.cardId = :cardId)
              and (:tripId is null or t.tripId = :tripId)
              and (:importId is null or t.importId = :importId)
              and (:product is null or t.product = :product)
              and (:country is null or t.country = :country)
              and (:unmatched = false or t.vehicleId is null)
              and (:q is null or lower(t.station) like :q or lower(t.productRaw) like :q
                   or lower(t.plateRaw) like :q)
            order by t.at desc, t.id desc""")
    List<FuelTransaction> search(@Param("from") Instant from, @Param("to") Instant to,
                                 @Param("vehicleId") Long vehicleId, @Param("driverId") Long driverId,
                                 @Param("cardId") Long cardId, @Param("tripId") Long tripId,
                                 @Param("importId") Long importId,
                                 @Param("product") FuelTransaction.Product product, @Param("country") String country,
                                 @Param("unmatched") boolean unmatched, @Param("q") String q);

    @Query("""
            select t.externalId from FuelTransaction t
            where t.provider = :provider and t.externalId in :externalIds""")
    List<String> existingExternalIds(@Param("provider") FuelProvider provider,
                                     @Param("externalIds") Collection<String> externalIds);

    List<FuelTransaction> findByImportId(Long importId);

    /**
     * Average price per unit of a product in a currency since {@code from}: net amounts (gross when net is unknown)
     * divided by the quantity; null without such transactions.
     */
    @Query("""
            select sum(coalesce(t.net, t.gross)) / sum(t.quantity) from FuelTransaction t
            where t.product = :product and t.currency = :currency and t.at >= :from and t.quantity > 0""")
    BigDecimal averagePrice(@Param("product") FuelTransaction.Product product,
                             @Param("currency") String currency, @Param("from") Instant from);

    /** Per currency: the average net price per unit and the quantity bought in a country since an instant. */
    @Query("""
            select t.currency, sum(coalesce(t.net, t.gross)) / sum(t.quantity), sum(t.quantity) from FuelTransaction t
            where t.product = :product and t.country = :country and t.at >= :from and t.quantity > 0
            group by t.currency""")
    List<Object[]> averagePriceInCountry(@Param("product") FuelTransaction.Product product,
                                         @Param("country") String country, @Param("from") Instant from);

    boolean existsByCardId(Long cardId);

    @Modifying
    @Query("update FuelTransaction t set t.cardId = null where t.cardId = :cardId")
    int detachCard(@Param("cardId") Long cardId);

    @Modifying
    @Query("update FuelTransaction t set t.tripId = null where t.tripId = :tripId")
    int detachTrip(@Param("tripId") Long tripId);
}

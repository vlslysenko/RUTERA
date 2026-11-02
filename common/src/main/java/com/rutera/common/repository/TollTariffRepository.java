package com.rutera.common.repository;

import com.rutera.common.domain.TollTariff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface TollTariffRepository extends JpaRepository<TollTariff, Long> {

    List<TollTariff> findAllByOrderByCountryAscValidFromDescIdAsc();

    List<TollTariff> findByCountryIn(Collection<String> countries);
}

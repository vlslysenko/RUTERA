package com.rutera.common.repository;

import com.rutera.common.domain.FuelCard;
import com.rutera.common.domain.FuelProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface FuelCardRepository extends JpaRepository<FuelCard, Long> {

    List<FuelCard> findAllByOrderByProviderAscCardNumberMaskedAsc();

    List<FuelCard> findByProvider(FuelProvider provider);

    Optional<FuelCard> findByProviderAndCardHash(FuelProvider provider, String cardHash);
}

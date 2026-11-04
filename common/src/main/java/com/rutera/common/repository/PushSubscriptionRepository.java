package com.rutera.common.repository;

import com.rutera.common.domain.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Tenant-filtered: results always belong to the firma of the current request context. Lookups by endpoint run in
 * root scope (a device may move to a user of another firma).
 */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    Optional<PushSubscription> findByEndpointHash(String endpointHash);

    List<PushSubscription> findByUserIdOrderByIdAsc(Long userId);

    List<PushSubscription> findByUserIdIn(Collection<Long> userIds);
}

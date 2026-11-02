package com.rutera.core.driver;

import com.rutera.common.domain.Driver;
import com.rutera.common.repository.DriverRepository;
import com.rutera.common.repository.TripDriverRepository;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * The driver cabinet's self access: endpoints that also accept {@code driver_app.self/use} serve a caller without
 * the full privilege only for the driver linked to the caller's login ({@code driver.user_id}); anything else is
 * 403 {@code auth.access_denied}.
 */
@Component
public class DriverSelf {

    private final DriverRepository drivers;
    private final TripDriverRepository crew;

    public DriverSelf(DriverRepository drivers, TripDriverRepository crew) {
        this.drivers = drivers;
        this.crew = crew;
    }

    /** Whether the caller has {@code privilege} itself (else the caller only sees its own driver). */
    public boolean full(String privilege) {
        return CurrentUser.require().hasPrivilege(privilege);
    }

    /** The driver linked to a login, if any. */
    public Optional<Driver> driverOf(Long userId) {
        return userId == null ? Optional.empty() : drivers.findByUserIdAndDeletedFalse(userId);
    }

    /** The caller's own driver id; 403 when the login has no driver. */
    public Long ownDriverId() {
        return driverOf(CurrentUser.require().id()).map(Driver::getId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED));
    }

    /** 403 unless {@code driverId} is the caller's own driver. */
    public void requireOwn(Long driverId) {
        if (!Objects.equals(driverId, ownDriverId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED);
        }
    }

    /** 403 unless the caller's own driver is MAIN or SECOND on the trip. */
    public void requireCrew(Long tripId) {
        Long own = ownDriverId();
        if (crew.findByTripId(tripId).stream().noneMatch(d -> own.equals(d.getDriverId()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED);
        }
    }

    /** Without {@code privilege}: the request must be about the caller's own driver. */
    public void check(String privilege, Long driverId) {
        if (!full(privilege)) {
            requireOwn(driverId);
        }
    }
}

package com.rutera.core.fuel;

import com.rutera.common.domain.FuelCard;
import com.rutera.common.domain.FuelTransaction;
import com.rutera.common.domain.Trip;
import com.rutera.common.domain.TripDriver;
import com.rutera.common.domain.Vehicle;
import com.rutera.common.repository.TripDriverRepository;
import com.rutera.common.repository.TripRepository;
import com.rutera.common.repository.VehicleRepository;
import com.rutera.core.fleet.VehicleService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Finds vehicle, driver and trip of a fuel transaction: the vehicle of the card, else the vehicle with the plate of
 * the export (same normalization as {@code vehicle.plate_key}); the trip of that vehicle whose actual (else planned)
 * period contains the time; the driver of the card, else the main driver of that trip.
 */
@Component
class FuelMatching {

    private final VehicleRepository vehicles;
    private final TripRepository trips;
    private final TripDriverRepository tripDrivers;

    FuelMatching(VehicleRepository vehicles, TripRepository trips, TripDriverRepository tripDrivers) {
        this.vehicles = vehicles;
        this.trips = trips;
        this.tripDrivers = tripDrivers;
    }

    /** Loads the firma's vehicles and the trips around the given times (current tenant). */
    Context context(Collection<Instant> times) {
        Map<String, Long> byPlate = new HashMap<>();
        for (Vehicle v : vehicles.findByDeletedFalseOrderByPlateAsc()) {
            byPlate.put(VehicleService.plateKey(v.getPlate()), v.getId());
        }
        Instant from = times.stream().filter(Objects::nonNull).min(Instant::compareTo).orElse(null);
        Instant to = times.stream().filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
        List<Trip> around = from == null ? List.of()
                : trips.activeBetween(from.minus(Duration.ofDays(1)), to.plus(Duration.ofDays(1)));
        Map<Long, Long> mainDriver = new HashMap<>();
        if (!around.isEmpty()) {
            for (TripDriver d : tripDrivers.findByTripIdIn(around.stream().map(Trip::getId).toList())) {
                if (d.getRole() == TripDriver.Role.MAIN) {
                    mainDriver.put(d.getTripId(), d.getDriverId());
                }
            }
        }
        return new Context(byPlate, around, mainDriver);
    }

    static final class Context {
        private final Map<String, Long> byPlate;
        private final List<Trip> trips;
        private final Map<Long, Long> mainDriver;

        private Context(Map<String, Long> byPlate, List<Trip> trips, Map<Long, Long> mainDriver) {
            this.byPlate = byPlate;
            this.trips = trips;
            this.mainDriver = mainDriver;
        }

        Long vehicleByPlate(String plateKey) {
            return plateKey == null || plateKey.isEmpty() ? null : byPlate.get(plateKey);
        }

        /** The vehicle's trip at that time (the latest started when several overlap). */
        Trip trip(Long vehicleId, Instant at) {
            if (vehicleId == null || at == null) {
                return null;
            }
            Trip best = null;
            for (Trip t : trips) {
                Instant start = t.getActualStart() != null ? t.getActualStart() : t.getPlannedStart();
                Instant end = t.getActualEnd() != null ? t.getActualEnd() : t.getPlannedEnd();
                if (vehicleId.equals(t.getVehicleId()) && start != null && !at.isBefore(start)
                        && (end == null || !at.isAfter(end))) {
                    Instant bestStart = best == null ? null
                            : best.getActualStart() != null ? best.getActualStart() : best.getPlannedStart();
                    if (best == null || start.isAfter(bestStart)) {
                        best = t;
                    }
                }
            }
            return best;
        }

        /** Sets vehicle, trip, driver and how they were found on an imported transaction. */
        void assign(FuelTransaction t, FuelCard card, String plateKey) {
            Long vehicleId = card == null ? null : card.getVehicleId();
            FuelTransaction.Match match = vehicleId != null ? FuelTransaction.Match.CARD : FuelTransaction.Match.NONE;
            if (vehicleId == null) {
                vehicleId = vehicleByPlate(plateKey);
                match = vehicleId != null ? FuelTransaction.Match.PLATE : FuelTransaction.Match.NONE;
            }
            Trip trip = trip(vehicleId, t.getAt());
            Long driverId = card == null ? null : card.getDriverId();
            if (driverId == null && trip != null) {
                driverId = mainDriver.get(trip.getId());
            }
            t.setVehicleId(vehicleId);
            t.setTripId(trip == null ? null : trip.getId());
            t.setDriverId(driverId);
            t.setMatchKind(driverId != null && match == FuelTransaction.Match.NONE ? FuelTransaction.Match.CARD : match);
        }
    }
}

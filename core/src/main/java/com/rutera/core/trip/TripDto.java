package com.rutera.core.trip;

import com.rutera.common.domain.TripDriver;
import com.rutera.common.domain.TripStop;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A trip with crew, orders and stops ({@code Trip} in the trips/orders contract). */
public record TripDto(
        Long id,
        String number,
        com.rutera.common.domain.Trip.Status status,
        VehicleRef vehicle,
        VehicleRef trailer,
        List<CrewMember> drivers,
        Instant plannedStart,
        Instant plannedEnd,
        Instant actualStart,
        Instant actualEnd,
        Integer kmPlanned,
        Integer kmActual,
        List<OrderRef> orders,
        List<Stop> stops,
        String notes,
        Instant createdAt,
        Instant updatedAt) {

    public record VehicleRef(Long id, String plate) {
    }

    public record CrewMember(Long id, String name, TripDriver.Role role) {
    }

    public record OrderRef(Long id, String number, ContractorDto.Ref customer, BigDecimal price, String currency) {
    }

    public record Stop(Long id, int seq, TripStop.Kind kind, Long orderId, String name, String address,
                       String country, Instant plannedAt, Instant actualAt, TripStop.Status status) {

        public static Stop of(TripStop s) {
            return new Stop(s.getId(), s.getSeq(), s.getKind(), s.getOrderId(), s.getName(), s.getAddress(),
                    s.getCountry(), s.getPlannedAt(), s.getActualAt(), s.getStatus());
        }
    }

    public record Page(List<TripDto> items, long total) {
    }

    /**
     * Body of {@code POST} and {@code PUT /app/trips[/{id}]} (PUT is a full replacement, orders and stops included).
     * An empty number gets the next one of the year; {@code status} defaults to PLANNED.
     */
    public record Input(
            @Size(max = 32) String number,
            Long vehicleId,
            Long trailerId,
            Long mainDriverId,
            Long secondDriverId,
            String status,
            Instant plannedStart,
            Instant plannedEnd,
            Instant actualStart,
            Instant actualEnd,
            @Min(0) @Max(100_000) Integer kmPlanned,
            @Min(0) @Max(100_000) Integer kmActual,
            List<Long> orderIds,
            @Valid @Size(max = 100) List<StopInput> stops,
            @Size(max = 2000) String notes) {
    }

    /** A stop of {@link Input}: with an id it updates that stop of the trip, without one it is new. */
    public record StopInput(
            Long id,
            String kind,
            Long orderId,
            @Size(max = 200) String name,
            @Size(max = 500) String address,
            @Size(max = 5) String country,
            Instant plannedAt,
            Instant actualAt,
            String status) {
    }

    /** Body of {@code PATCH /app/trips/{id}/stops/{stopId}}. */
    public record StopStatusInput(String status, Instant actualAt) {
    }
}

package com.rutera.core.trip;

import com.rutera.common.domain.Contractor;
import com.rutera.common.domain.TransportOrder;
import com.rutera.common.domain.Trip;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** A customer order ({@code Order} in the trips/orders contract). */
public record OrderDto(
        Long id,
        String number,
        LocalDate orderDate,
        ContractorDto.Ref customer,
        String customerRef,
        BigDecimal price,
        String currency,
        String cargo,
        Integer weightKg,
        Place loading,
        Place unloading,
        TransportOrder.Status status,
        TripRef trip,
        String notes,
        Instant createdAt,
        Instant updatedAt) {

    public record Place(String place, String country, LocalDate date) {
    }

    public record TripRef(Long id, String number, Trip.Status status) {
    }

    public static OrderDto of(TransportOrder o, Contractor customer, Trip trip) {
        return new OrderDto(o.getId(), o.getNumber(), o.getOrderDate(), ContractorDto.Ref.of(customer),
                o.getCustomerRef(), o.getPrice(), o.getCurrency(), o.getCargo(), o.getWeightKg(),
                new Place(o.getLoadingPlace(), o.getLoadingCountry(), o.getLoadingDate()),
                new Place(o.getUnloadingPlace(), o.getUnloadingCountry(), o.getUnloadingDate()),
                o.getStatus(), trip == null ? null : new TripRef(trip.getId(), trip.getNumber(), trip.getStatus()),
                o.getNotes(), o.getCreatedAt(), o.getUpdatedAt());
    }

    public record Page(List<OrderDto> items, long total) {
    }

    /**
     * Body of {@code POST} and {@code PUT /app/orders[/{id}]} (PUT is a full replacement). An empty number gets the
     * next one of the year; {@code currency} is required with {@code price}; {@code cancelled: true} cancels the
     * order (and takes it off its trip), false brings a cancelled order back.
     */
    public record Input(
            @Size(max = 32) String number,
            @NotNull LocalDate orderDate,
            @NotNull Long customerId,
            @Size(max = 64) String customerRef,
            @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal price,
            @Size(max = 3) String currency,
            @Size(max = 500) String cargo,
            @Min(0) @Max(100_000) Integer weightKg,
            @NotBlank @Size(max = 200) String loadingPlace,
            @NotBlank @Size(max = 5) String loadingCountry,
            LocalDate loadingDate,
            @NotBlank @Size(max = 200) String unloadingPlace,
            @NotBlank @Size(max = 5) String unloadingCountry,
            LocalDate unloadingDate,
            Boolean cancelled,
            @Size(max = 2000) String notes) {
    }

    /** Body of {@code POST /app/orders/{id}/trip}: a trip for this order in one step. */
    public record TripRequest(
            @NotNull Long vehicleId,
            Long trailerId,
            Long mainDriverId,
            Long secondDriverId,
            Instant plannedStart) {
    }
}

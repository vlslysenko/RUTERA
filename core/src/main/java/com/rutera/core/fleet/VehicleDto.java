package com.rutera.core.fleet;

import com.rutera.common.domain.Vehicle;
import com.rutera.common.domain.VehicleDocument;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A vehicle or trailer as the fleet screens show it ({@code Vehicle} in the frontend contract). */
public record VehicleDto(
        Long id,
        Vehicle.Kind kind,
        String plate,
        String vin,
        String make,
        String model,
        Vehicle.EuroClass euroClass,
        Integer axles,
        Integer grossWeightKg,
        Integer heightMm,
        BigDecimal avgL100km,
        Vehicle.FuelType fuelType,
        Vehicle.TachoGen tachoGen,
        Integer odometerKm,
        Vehicle.Status status,
        DriverRef defaultDriver,
        VehicleRef defaultTrailer,
        Instant lastVuDownloadAt,
        Deadline nextDocument,
        Instant createdAt,
        Instant updatedAt) {

    public record DriverRef(Long id, String name) {
    }

    public record VehicleRef(Long id, String plate) {
    }

    /** The nearest document deadline (already expired ones included, they come first). */
    public record Deadline(VehicleDocument.Kind kind, LocalDate validTo) {
    }

    /**
     * @param trailer       the default trailer, already loaded (or null)
     * @param nextDocument  the document with the nearest {@code validTo} (or null)
     */
    public static VehicleDto of(Vehicle v, Vehicle trailer, VehicleDocument nextDocument) {
        // defaultDriver stays null until the driver module (group 3) exists.
        return new VehicleDto(v.getId(), v.getKind(), v.getPlate(), v.getVin(), v.getMake(), v.getModel(),
                v.getEuroClass(), v.getAxles(), v.getGrossWeightKg(), v.getHeightMm(), v.getAvgL100km(),
                v.getFuelType(), v.getTachoGen(), v.getOdometerKm(), v.getStatus(),
                null,
                trailer == null ? null : new VehicleRef(trailer.getId(), trailer.getPlate()),
                v.getLastVuDownloadAt(),
                nextDocument == null ? null : new Deadline(nextDocument.getKind(), nextDocument.getValidTo()),
                v.getCreatedAt(), v.getUpdatedAt());
    }
}

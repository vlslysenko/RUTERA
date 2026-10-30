package com.rutera.core.fleet;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Body of {@code POST} and {@code PUT /app/fleet/vehicles[/{id}]}: the whole editable part of a vehicle (PUT is a
 * full replacement). Enumerations arrive as strings so that an unknown value is a per-field
 * {@code validation.invalid_value}, not a 400. {@code tachoGen} defaults to NONE and {@code status} to ACTIVE.
 * For trailers the engine fields (tachograph, fuel, consumption, odometer, driver, trailer) are ignored.
 */
public record VehicleInput(
        @NotBlank String kind,
        @NotBlank @Size(max = 20) String plate,
        @Size(max = 25) String vin,
        @Size(max = 60) String make,
        @Size(max = 60) String model,
        String euroClass,
        @Min(1) @Max(10) Integer axles,
        @Min(1) @Max(100_000) Integer grossWeightKg,
        @Min(500) @Max(6_000) Integer heightMm,
        @DecimalMin("0.0") @DecimalMax("999.99") @Digits(integer = 3, fraction = 2) BigDecimal avgL100km,
        String fuelType,
        String tachoGen,
        @Min(0) @Max(99_999_999) Integer odometerKm,
        String status,
        Long defaultDriverId,
        Long defaultTrailerId) {
}

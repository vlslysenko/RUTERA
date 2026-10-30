package com.rutera.core.fleet;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body of {@code POST} and {@code PUT /app/fleet/vehicles/{id}/documents[/{documentId}]} (PUT is a full
 * replacement). Dates are {@code YYYY-MM-DD}; {@code currency} (ISO 4217) is required together with
 * {@code cost}; {@code remindDays} defaults to 30.
 */
public record VehicleDocumentInput(
        @NotBlank String kind,
        @Size(max = 64) String number,
        LocalDate validFrom,
        LocalDate validTo,
        @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal cost,
        @Size(max = 3) String currency,
        @Min(0) @Max(365) Integer remindDays) {
}

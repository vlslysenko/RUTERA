package com.rutera.core.driver;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Body of {@code POST} and {@code PUT /app/drivers/{id}/documents[/{documentId}]} (PUT is a full replacement).
 * Dates are {@code YYYY-MM-DD}; {@code remindDays} defaults to 30.
 */
public record DriverDocumentInput(
        @NotBlank String kind,
        @Size(max = 64) String number,
        LocalDate validFrom,
        LocalDate validTo,
        @Min(0) @Max(365) Integer remindDays) {
}

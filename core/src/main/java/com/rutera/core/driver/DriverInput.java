package com.rutera.core.driver;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Body of {@code POST} and {@code PUT /app/drivers[/{id}]}: the whole editable part of a driver including the
 * tachograph card (PUT is a full replacement). An empty {@code cardNumber} removes the card; with a card number,
 * {@code cardIssuingCountry} is required. Dates are {@code YYYY-MM-DD}; {@code status} defaults to ACTIVE.
 */
public record DriverInput(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        LocalDate birthDate,
        @Size(max = 40) String phone,
        @Size(max = 254) String email,
        @Size(max = 5) String country,
        LocalDate employmentFrom,
        LocalDate employmentTo,
        String status,
        @Size(max = 2000) String notes,
        @Size(max = 40) String cardNumber,
        @Size(max = 5) String cardIssuingCountry,
        LocalDate cardValidTo) {
}

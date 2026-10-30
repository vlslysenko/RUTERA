package com.rutera.core.driver;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Driver;
import com.rutera.common.domain.DriverCard;
import com.rutera.common.domain.Vehicle;
import com.rutera.core.fleet.VehicleDto;

import java.time.Instant;
import java.time.LocalDate;

/** A driver as the driver screens show it ({@code Driver} in the frontend contract). */
public record DriverDto(
        Long id,
        String firstName,
        String lastName,
        String name,
        LocalDate birthDate,
        String phone,
        String email,
        String country,
        LocalDate employmentFrom,
        LocalDate employmentTo,
        Driver.Status status,
        VehicleDto.VehicleRef defaultVehicle,
        Card card,
        UserRef user,
        String notes,
        Deadline nextDocument,
        Instant createdAt,
        Instant updatedAt) {

    /** The driver's tachograph card; {@code lastDownloadAt} is written by the DDD import. */
    public record Card(String cardNumber, String issuingCountry, LocalDate validTo, Instant lastDownloadAt) {
    }

    public record UserRef(Long id, String login) {
    }

    /** The nearest deadline: a document kind, or {@code DRIVER_CARD} for the tachograph card. */
    public record Deadline(String kind, LocalDate validTo) {
    }

    public static final String CARD_DEADLINE = "DRIVER_CARD";

    public static DriverDto of(Driver d, Vehicle vehicle, DriverCard card, AppUser user, Deadline next) {
        return new DriverDto(d.getId(), d.getFirstName(), d.getLastName(), d.getFullName(), d.getBirthDate(),
                d.getPhone(), d.getEmail(), d.getCountry(), d.getEmploymentFrom(), d.getEmploymentTo(), d.getStatus(),
                vehicle == null ? null : new VehicleDto.VehicleRef(vehicle.getId(), vehicle.getPlate()),
                card == null ? null : new Card(card.getCardNumber(), card.getIssuingCountry(), card.getValidTo(),
                        card.getLastDownloadAt()),
                user == null ? null : new UserRef(user.getId(), user.getLogin()),
                d.getNotes(), next, d.getCreatedAt(), d.getUpdatedAt());
    }
}

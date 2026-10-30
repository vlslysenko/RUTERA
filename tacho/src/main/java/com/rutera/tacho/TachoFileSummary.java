package com.rutera.tacho;

import java.time.Instant;
import java.time.LocalDate;

/**
 * What the import stores in {@code tacho_file} and uses to find the driver or the vehicle.
 *
 * @param cardNumber driver card: the 16 character card number; null for a VU file
 * @param cardIssuingCountry ISO 3166-1 alpha-2 of the issuing state (tachograph code if there is no ISO code)
 * @param cardExpiry driver card expiry date (UTC)
 * @param vin vehicle unit: the VIN of the vehicle; null for a card
 * @param plate vehicle unit: the registration plate; null for a card
 * @param plateCountry ISO 3166-1 alpha-2 of the registering state, null if not recorded
 * @param periodFrom first day with activity data (UTC)
 * @param periodTo last day with activity data (UTC)
 * @param downloadedAt vehicle unit: VU time at the download; driver card: the later of EF Card_Download and the
 *                     newest recorded data (many download tools do not update EF Card_Download before reading)
 */
public record TachoFileSummary(FileKind kind, Generation generation, String cardNumber, String cardIssuingCountry,
                               LocalDate cardExpiry, String vin, String plate, String plateCountry,
                               LocalDate periodFrom, LocalDate periodTo, Instant downloadedAt) {
}

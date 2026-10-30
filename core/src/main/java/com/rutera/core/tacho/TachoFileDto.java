package com.rutera.core.tacho;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Driver;
import com.rutera.common.domain.StoredFile;
import com.rutera.common.domain.TachoFileRecord;
import com.rutera.common.domain.Vehicle;
import com.rutera.core.driver.DriverDto;
import com.rutera.core.fleet.VehicleDto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** An uploaded DDD file in the journal ({@code TachoFile} in docs/TACHO_IMPORT_API.md). */
public record TachoFileDto(
        Long id,
        TachoFileRecord.Kind kind,
        TachoFileRecord.Generation generation,
        String originalName,
        long sizeBytes,
        String sha256,
        String cardNumber,
        String cardIssuingCountry,
        LocalDate cardValidTo,
        String vin,
        String plate,
        String plateCountry,
        LocalDate periodFrom,
        LocalDate periodTo,
        Instant downloadedAt,
        int warningsCount,
        VehicleDto.DriverRef driver,
        VehicleDto.VehicleRef vehicle,
        TachoFileRecord.Match match,
        UserRef uploadedBy,
        Instant uploadedAt) {

    public record UserRef(Long id, String name) {
    }

    public static TachoFileDto of(TachoFileRecord f, StoredFile stored, Driver driver, Vehicle vehicle, AppUser by) {
        return new TachoFileDto(f.getId(), f.getKind(), f.getGeneration(),
                stored == null ? null : stored.getOriginalName(), stored == null ? 0 : stored.getSizeBytes(),
                f.getSha256(), f.getCardNumber(), f.getCardIssuingCountry(), f.getCardValidTo(), f.getVin(),
                f.getPlate(), f.getPlateCountry(), f.getPeriodFrom(), f.getPeriodTo(), f.getDownloadedAt(),
                f.getWarningsCount(),
                driver == null ? null : new VehicleDto.DriverRef(driver.getId(), driver.getFullName()),
                vehicle == null ? null : new VehicleDto.VehicleRef(vehicle.getId(), vehicle.getPlate()),
                f.getMatch(),
                by == null ? null : new UserRef(by.getId(), by.getFullName().trim()),
                f.getUploadedAt());
    }

    /** {@code TachoImportResult}: one entry per uploaded file, in the order of the request. */
    public record ImportResult(String fileName, Status status, TachoFileDto file, Error error) {

        public enum Status {
            IMPORTED, DUPLICATE, REJECTED
        }

        public record Error(String code, String message) {
        }
    }

    /** {@code TachoFileDetail}: the journal entry (unwrapped) plus what the screen needs to complete a file. */
    public record Detail(
            @JsonUnwrapped TachoFileDto file,
            List<String> warnings,
            Counts counts,
            Map<String, Object> suggestion,
            List<VuDriver> drivers) {
    }

    public record Counts(int activityDays, int vehiclesUsed, int places, int events, int faults, int cardInsertions,
                         int calibrations) {
    }

    /** Vehicle unit: a driver card that was inserted during the downloaded period. */
    public record VuDriver(String cardNumber, String name, Long driverId, Instant firstInsertion,
                           Instant lastWithdrawal) {
    }

    /** {@code PUT /{id}/assignment}: {@code driverId} for a driver card, {@code vehicleId} for a vehicle unit. */
    public record AssignmentInput(Long driverId, Long vehicleId) {
    }

    public record CreatedDriver(DriverDto driver, TachoFileDto file) {
    }

    public record CreatedVehicle(VehicleDto vehicle, TachoFileDto file) {
    }

    public record Page(List<TachoFileDto> items, long total) {
    }
}

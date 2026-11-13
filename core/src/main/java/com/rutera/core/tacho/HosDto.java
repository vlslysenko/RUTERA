package com.rutera.core.tacho;

import com.rutera.common.domain.DriverViolation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Driver activity, 561/2006 figures and violations (docs/TACHO_IMPORT_API.md steps B and D, frontend contract). */
public final class HosDto {

    private HosDto() {
    }

    public record Ref(Long id, String name) {
    }

    /** The vehicle of an interval or violation; {@code id} is null when the plate or VIN is not in the fleet. */
    public record VehicleRef(Long id, String plate) {
    }

    public record Interval(Instant startAt, Instant endAt, String activity, int slot, VehicleRef vehicle) {
    }

    public record Day(LocalDate day, int drivingMin, int workMin, int availabilityMin, int restMin,
                      Integer dailyRestMin, int extDrivingInWeek, int reducedRestInWeek, int weeklyDrivingMin,
                      int biweeklyDrivingMin, int remainingDailyMin, int remainingWeeklyMin,
                      Instant nextWeeklyRestBy, int violations) {
    }

    /** {@code GET /app/tacho/activity}. */
    public record Activity(Ref driver, String timezone, List<Day> days, List<Interval> intervals) {
    }

    public record Ack(Ref by, Instant at, String note) {
    }

    public record Violation(Long id, Ref driver, DriverViolation.Rule rule, DriverViolation.Severity severity,
                            Instant startAt, Instant endAt, int actualValue, int limitValue, VehicleRef vehicle,
                            String details, Ack ack, Long explanationId, String explanationStatus) {
    }

    public record Page(List<Violation> items, long total) {
    }

    /** Body of {@code POST /app/tacho/violations/{id}/ack}. */
    public record AckInput(String note) {
    }
}

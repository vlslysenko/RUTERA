package com.rutera.core.driver;

import com.rutera.common.domain.DriverAbsence;
import com.rutera.common.domain.DriverDay;
import com.rutera.common.domain.DriverPayment;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Driver HR: absences, working days, pay rates and payments (contract of 2026-11-02). */
public final class HrDto {

    private HrDto() {
    }

    public record Ref(Long id, String name) {
    }

    public record Absence(Long id, Ref driver, DriverAbsence.Kind kind, LocalDate fromDate, LocalDate toDate, int days,
                          int workingDays, boolean paid, String note, Ref approvedBy, Instant approvedAt,
                          Instant createdAt, Instant updatedAt) {
    }

    /** Body of {@code POST}/{@code PUT .../absences}; {@code paid} defaults to false for UNPAID, true otherwise. */
    public record AbsenceInput(String kind, LocalDate fromDate, LocalDate toDate, Boolean paid,
                               @Size(max = 500) String note) {
    }

    public enum DaySource {
        TACHO, ABSENCE, MANUAL, NONE
    }

    public record Day(LocalDate day, DriverDay.Kind kind, DaySource source, DriverAbsence.Kind absenceKind,
                      int drivingMin, int workMin, String note) {
    }

    public record Summary(int workDays, int restDays, int absenceDays, int holidayDays, int drivingMin, int workMin) {
    }

    public record Month(List<Day> days, Summary summary) {
    }

    /** Body of {@code PUT /app/drivers/{id}/days/{date}}. */
    public record DayInput(String kind, @Size(max = 500) String note) {
    }

    public record PayRate(Long id, LocalDate validFrom, BigDecimal monthlyBase, BigDecimal perDiemHome,
                          BigDecimal perDiemAbroad, BigDecimal perKm, String currency, Instant createdAt,
                          Instant updatedAt) {
    }

    /** Body of {@code POST}/{@code PUT .../pay-rates}: at least one amount. */
    public record PayRateInput(
            LocalDate validFrom,
            @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal monthlyBase,
            @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal perDiemHome,
            @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal perDiemAbroad,
            @DecimalMin("0.0000") @Digits(integer = 6, fraction = 4) BigDecimal perKm,
            @Size(max = 3) String currency) {
    }

    public record Payment(Long id, Ref driver, String period, DriverPayment.Kind kind, BigDecimal amount,
                          String currency, LocalDate paidAt, String note, Instant createdAt, Instant updatedAt) {
    }

    /** Body of {@code POST}/{@code PUT .../payments}: period YYYY-MM, amount above zero. */
    public record PaymentInput(
            String period,
            String kind,
            @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @Size(max = 3) String currency,
            LocalDate paidAt,
            @Size(max = 500) String note) {
    }

    /** What is owed per currency: advances and deductions count negative. */
    public record Total(String currency, BigDecimal amount) {
    }

    public record PaymentList(List<Payment> items, List<Total> totals) {
    }
}

package com.rutera.core.fuel;

import com.rutera.common.domain.FuelImport;
import com.rutera.common.domain.FuelProvider;
import com.rutera.common.domain.FuelTransaction;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Fuel cards, imports of provider exports and fuel transactions (contract of 2026-11-02). */
public final class FuelDto {

    private FuelDto() {
    }

    public record Ref(Long id, String name) {
    }

    public record CardRef(Long id, String masked) {
    }

    public record Card(Long id, FuelProvider provider, String cardNumberMasked, Ref vehicle, Ref driver,
                       LocalDate validTo, boolean active, String notes, Instant createdAt, Instant updatedAt) {
    }

    /** Body of {@code POST}/{@code PUT /app/fuel/cards}; on PUT a blank number keeps the card's number. */
    public record CardInput(String provider, @Size(max = 40) String cardNumber, Long vehicleId, Long driverId,
                            LocalDate validTo, Boolean active, @Size(max = 500) String notes) {
    }

    public record Transaction(Long id, Instant at, FuelProvider provider, String externalId, CardRef card, Ref vehicle,
                              Ref driver, Ref trip, String station, String country, FuelTransaction.Product product,
                              String productRaw, BigDecimal quantity, FuelTransaction.Unit unit, BigDecimal unitPrice,
                              BigDecimal net, BigDecimal vat, BigDecimal gross, String currency, Integer odometerKm,
                              String plateRaw, FuelTransaction.Match match, FuelTransaction.Source source,
                              Long importId, String notes, Instant createdAt) {
    }

    public record Total(String currency, BigDecimal net, BigDecimal vat, BigDecimal gross) {
    }

    public record Quantity(FuelTransaction.Product product, FuelTransaction.Unit unit, BigDecimal quantity) {
    }

    public record Page(List<Transaction> items, long total, List<Total> totals, List<Quantity> quantities) {
    }

    /** Body of {@code POST}/{@code PUT /app/fuel/transactions} (entered by hand; PUT only for those). */
    public record TransactionInput(
            Instant at,
            Long cardId,
            Long vehicleId,
            Long driverId,
            Long tripId,
            @Size(max = 200) String station,
            @Size(max = 3) String country,
            String product,
            @Size(max = 100) String productRaw,
            @DecimalMin("0.000") @Digits(integer = 9, fraction = 3) BigDecimal quantity,
            String unit,
            @Digits(integer = 8, fraction = 4) BigDecimal unitPrice,
            @Digits(integer = 12, fraction = 2) BigDecimal net,
            @Digits(integer = 12, fraction = 2) BigDecimal vat,
            @Digits(integer = 12, fraction = 2) BigDecimal gross,
            @Size(max = 3) String currency,
            Integer odometerKm,
            @Size(max = 500) String notes) {
    }

    /** Body of {@code PATCH /app/fuel/transactions/{id}}: replaces the assignment of any transaction. */
    public record AssignInput(Long vehicleId, Long driverId, Long tripId, @Size(max = 500) String notes) {
    }

    public record RowError(int row, String field, String code) {
    }

    public record Import(Long id, FuelProvider provider, String fileName, LocalDate periodFrom, LocalDate periodTo,
                         int rowsTotal, int rowsNew, int rowsDup, int rowsError, FuelImport.Status status,
                         List<RowError> errors, Map<String, String> mapping, Ref importedBy, Instant createdAt) {
    }

    public record ImportPage(List<Import> items, long total) {
    }

    /** Parsing options of an export; null = guess (decimal separator, date order) or the firma's base currency. */
    public record Options(String dateFormat, Boolean decimalComma, String currency) {
    }

    public record Mapping(FuelProvider provider, Map<String, String> columns, Options options) {
    }

    /** Body of {@code PUT /app/fuel/mappings/{provider}}. */
    public record MappingInput(Map<String, String> columns, Options options) {
    }

    /**
     * One data row as the import would read it (1-based {@code row} of the file). {@code values} by field key: at
     * (instant), cardNumber (masked), plate, transactionId, station, country, product (group), productRaw, quantity,
     * unit, unitPrice, net, vat, gross, currency, odometer; null when empty. {@code errors}: field key -> code.
     */
    public record PreviewRow(int row, Map<String, Object> values, Map<String, String> errors) {
    }

    /** {@code mapping} holds every field key, null when no column was found; {@code headerRow} is 1-based. */
    public record Preview(FuelProvider provider, int headerRow, List<String> headers, Map<String, String> mapping,
                          List<String> missing, Options options, int dataRows, List<PreviewRow> rows) {
    }
}

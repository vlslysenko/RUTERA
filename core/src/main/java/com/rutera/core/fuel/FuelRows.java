package com.rutera.core.fuel;

import com.rutera.common.domain.FuelTransaction;
import com.rutera.core.fleet.VehicleService;
import com.rutera.core.fuel.read.ColumnMapper;
import com.rutera.core.fuel.read.ColumnMapper.Field;
import com.rutera.core.fuel.read.FuelValues;
import com.rutera.core.web.ErrorCodes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads the data rows of a detected export into transaction values, with per-field errors. */
final class FuelRows {

    /** One data row; {@code errors} empty when it can be imported. */
    record Parsed(int row, LocalDateTime local, Instant at, String card, String plate, String plateKey,
                  String transactionId, String station, String country, FuelTransaction.Product product,
                  String productRaw, BigDecimal quantity, FuelTransaction.Unit unit, BigDecimal unitPrice,
                  BigDecimal net, BigDecimal vat, BigDecimal gross, String currency, Integer odometerKm,
                  Map<String, String> errors) {

        /**
         * The duplicate key: the provider's transaction id (when the export has one) plus a hash of card, plate,
         * time, product, quantity and amount, so a receipt number repeated at another station is no duplicate.
         */
        String externalId() {
            String digest = sha256(String.join("|", card, plate == null ? "" : plate, String.valueOf(local),
                    productRaw == null ? "" : productRaw, quantity == null ? "" : quantity.toPlainString(),
                    gross.toPlainString())).substring(0, 32);
            if (transactionId == null) {
                return "H:" + digest;
            }
            String id = transactionId.length() > 40 ? transactionId.substring(0, 40) : transactionId;
            return id + ":" + digest;
        }
    }

    private final ColumnMapper.Detection detection;
    private final FuelDto.Options options;
    private final ZoneId zone;
    private final String defaultCurrency;

    FuelRows(ColumnMapper.Detection detection, FuelDto.Options options, ZoneId zone, String defaultCurrency) {
        this.detection = detection;
        this.options = options == null ? new FuelDto.Options(null, null, null) : options;
        this.zone = zone;
        this.defaultCurrency = defaultCurrency;
    }

    /** The fields every export needs: date, product, an amount (gross or net) and the card or the plate. */
    static List<String> missing(Map<Field, Integer> columns) {
        List<String> missing = new ArrayList<>();
        if (!columns.containsKey(Field.DATE)) {
            missing.add(Field.DATE.key);
        }
        if (!columns.containsKey(Field.PRODUCT)) {
            missing.add(Field.PRODUCT.key);
        }
        if (!columns.containsKey(Field.GROSS) && !columns.containsKey(Field.NET)) {
            missing.add(Field.GROSS.key);
        }
        if (!columns.containsKey(Field.CARD_NUMBER) && !columns.containsKey(Field.PLATE)) {
            missing.add(Field.CARD_NUMBER.key);
        }
        return missing;
    }

    private String cell(List<String> row, Field f) {
        Integer c = detection.columns().get(f);
        if (c == null || c >= row.size()) {
            return null;
        }
        String v = row.get(c);
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** {@code rowNumber} is 1-based as in a spreadsheet. */
    Parsed parse(List<String> row, int rowNumber) {
        Map<String, String> errors = new LinkedHashMap<>();
        LocalDateTime local = null;
        Instant at = null;
        try {
            local = FuelValues.dateTime(cell(row, Field.DATE), cell(row, Field.TIME), options.dateFormat());
            at = local.atZone(zone).toInstant();
        } catch (DateTimeException | IllegalArgumentException e) {
            errors.put(Field.DATE.key, cell(row, Field.DATE) == null ? ErrorCodes.FIELD_REQUIRED
                    : ErrorCodes.FIELD_INVALID_FORMAT);
        }
        String card = FuelValues.cardNumber(cell(row, Field.CARD_NUMBER));
        String plateRaw = cell(row, Field.PLATE);
        String plate = plateRaw == null ? null : truncate(plateRaw.toUpperCase(Locale.ROOT), 20);
        String plateKey = plate == null ? null : VehicleService.plateKey(plate);
        if (card.isEmpty() && plate == null) {
            errors.put(Field.CARD_NUMBER.key, ErrorCodes.FIELD_REQUIRED);
        }
        String productRaw = truncate(cell(row, Field.PRODUCT), 100);
        if (productRaw == null) {
            errors.put(Field.PRODUCT.key, ErrorCodes.FIELD_REQUIRED);
        }
        FuelTransaction.Product product = FuelValues.product(productRaw);
        BigDecimal quantity = amount(row, Field.QUANTITY, 3, errors);
        BigDecimal unitPrice = amount(row, Field.UNIT_PRICE, 4, errors);
        BigDecimal net = amount(row, Field.NET, 2, errors);
        BigDecimal vat = amount(row, Field.VAT, 2, errors);
        BigDecimal gross = amount(row, Field.GROSS, 2, errors);
        if (gross == null && net != null) {
            gross = net.add(vat == null ? BigDecimal.ZERO : vat);
        }
        if (net == null && gross != null && vat != null) {
            net = gross.subtract(vat);
        }
        if (vat == null && gross != null && net != null) {
            vat = gross.subtract(net);
        }
        if (gross == null && !errors.containsKey(Field.GROSS.key) && !errors.containsKey(Field.NET.key)) {
            errors.put(Field.GROSS.key, ErrorCodes.FIELD_REQUIRED);
        }
        FuelTransaction.Unit unit = FuelValues.unit(cell(row, Field.UNIT));
        if (unit == null && quantity != null) {
            unit = switch (product) {
                case DIESEL, PETROL, ADBLUE -> FuelTransaction.Unit.L;
                case LNG, CNG -> FuelTransaction.Unit.KG;
                default -> null;
            };
        }
        String currency;
        String rawCurrency = cell(row, Field.CURRENCY);
        if (rawCurrency != null) {
            currency = FuelValues.currency(rawCurrency);
            if (currency == null) {
                errors.put(Field.CURRENCY.key, ErrorCodes.FIELD_INVALID_VALUE);
            }
        } else {
            currency = options.currency() != null ? options.currency() : defaultCurrency;
        }
        Integer odometer = null;
        BigDecimal km = amount(row, Field.ODOMETER, 0, errors);
        if (km != null) {
            if (km.signum() < 0 || km.compareTo(BigDecimal.valueOf(9_999_999)) > 0) {
                errors.put(Field.ODOMETER.key, ErrorCodes.FIELD_INVALID_VALUE);
            } else {
                odometer = km.intValue();
            }
        }
        return new Parsed(rowNumber, local, at, card, plate, plateKey, truncate(cell(row, Field.TRANSACTION_ID), 64),
                truncate(cell(row, Field.STATION), 200), FuelValues.country(cell(row, Field.COUNTRY)), product,
                productRaw, quantity, unit, unitPrice, net, vat, gross, currency, odometer, errors);
    }

    private BigDecimal amount(List<String> row, Field f, int scale, Map<String, String> errors) {
        String raw = cell(row, f);
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal v = FuelValues.decimal(raw, options.decimalComma());
            if (v == null) {
                return null;
            }
            v = v.setScale(scale, RoundingMode.HALF_UP);
            if (v.abs().compareTo(BigDecimal.valueOf(1_000_000_000L)) >= 0) {
                errors.put(f.key, ErrorCodes.FIELD_INVALID_VALUE);
                return null;
            }
            return v;
        } catch (NumberFormatException | ArithmeticException e) {
            errors.put(f.key, ErrorCodes.FIELD_INVALID_FORMAT);
            return null;
        }
    }

    static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    static String sha256(String text) {
        return sha256Bytes(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Bytes(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

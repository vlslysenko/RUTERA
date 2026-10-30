package com.rutera.core.web;

import com.rutera.core.security.CurrentUser;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared checks of the firma-scoped CRUD services (fleet, drivers): each one records a per-field code in
 * {@code errors} instead of throwing, so one 422 lists every problem of a form.
 */
public final class InputFields {

    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    private InputFields() {
    }

    /** The firma of the signed-in user; the service superuser has none, so firma data is 403 for them. */
    public static Long requireFirmaId() {
        Long firmaId = CurrentUser.require().firmaId();
        if (firmaId == null) {
            throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED);
        }
        return firmaId;
    }

    /** Case-insensitive enum value; blank is null (or {@code validation.required}), unknown is invalid_value. */
    public static <E extends Enum<E>> E parseEnum(Class<E> type, String field, String raw, boolean required,
                                                  Map<String, String> errors) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                errors.put(field, ErrorCodes.FIELD_REQUIRED);
            }
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.put(field, ErrorCodes.FIELD_INVALID_VALUE);
            return null;
        }
    }

    /** ISO 3166-1 alpha-2, upper-cased; blank is null. */
    public static String country(String field, String raw, Map<String, String> errors) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        value = value.toUpperCase(Locale.ROOT);
        if (!COUNTRY.matcher(value).matches()) {
            errors.put(field, ErrorCodes.FIELD_INVALID_FORMAT);
            return null;
        }
        return value;
    }

    /** {@code to} must not be before {@code from} when both are given. */
    public static void dateOrder(String toField, LocalDate from, LocalDate to, Map<String, String> errors) {
        if (from != null && to != null && to.isBefore(from)) {
            errors.put(toField, ErrorCodes.FIELD_INVALID_VALUE);
        }
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

package com.rutera.core.web;

/** i18n keys used as error codes by the web layer (texts in {@code messages_xx.properties}). */
public final class ErrorCodes {

    public static final String UNAUTHENTICATED = "auth.unauthenticated";
    public static final String ACCESS_DENIED = "auth.access_denied";
    public static final String CSRF_INVALID = "auth.csrf_invalid";
    public static final String LOGIN_INVALID = "auth.login.invalid";
    public static final String LOGIN_LOCKED = "auth.login.locked";
    public static final String LOGIN_DISABLED = "auth.login.disabled";
    public static final String LOGIN_FIRMA_INACTIVE = "auth.login.firma_inactive";

    public static final String INTERNAL = "error.internal";
    public static final String VALIDATION = "error.validation";
    public static final String NOT_FOUND = "error.not_found";
    public static final String BAD_REQUEST = "error.bad_request";
    public static final String CONFLICT = "error.conflict";
    public static final String METHOD_NOT_ALLOWED = "error.method_not_allowed";

    /** Per-field codes in {@link ErrorBody#details()}. */
    public static final String FIELD_REQUIRED = "validation.required";
    public static final String FIELD_INVALID_VALUE = "validation.invalid_value";
    public static final String FIELD_TOO_LONG = "validation.too_long";
    public static final String FIELD_TOO_SHORT = "validation.too_short";
    public static final String FIELD_INVALID_FORMAT = "validation.invalid_format";

    /** Passwords and user administration (per-field unless noted). */
    public static final String PASSWORD_WRONG_CURRENT = "auth.password.wrong_current";
    public static final String USER_LOGIN_TAKEN = "admin.user.login_taken";
    /** Top-level 403: the actor's role level is not high enough for the target user or the requested role. */
    public static final String USER_ROLE_NOT_ALLOWED = "admin.user.role_not_allowed";
    /** Top-level 403: a user may not change their own role or status, nor delete themselves. */
    public static final String USER_SELF_FORBIDDEN = "admin.user.self_forbidden";

    /** Fleet (per-field): another non-deleted vehicle of the firma has this plate. */
    public static final String VEHICLE_PLATE_TAKEN = "fleet.vehicle.plate_taken";
    /** Drivers (per-field): another driver of the firma has this tachograph card. */
    public static final String DRIVER_CARD_TAKEN = "driver.card.number_taken";
    /** Drivers (per-field): the login is already linked to another driver. */
    public static final String DRIVER_USER_TAKEN = "driver.user.taken";
    /** Driver HR (per-field): an absence overlaps another one of the driver; a pay rate starts on a taken date. */
    public static final String ABSENCE_OVERLAP = "driver.absence.overlap";
    public static final String PAY_RATE_DATE_TAKEN = "driver.pay_rate.date_taken";

    /** Tachograph import: per-file codes of a rejected upload, and the request limits. */
    public static final String TACHO_NOT_TACHOGRAPH = "tacho.file.not_tachograph";
    public static final String TACHO_TRUNCATED = "tacho.file.truncated";
    public static final String TACHO_UNSUPPORTED = "tacho.file.unsupported";
    public static final String TACHO_CORRUPT = "tacho.file.corrupt";
    public static final String TACHO_TOO_LARGE = "tacho.file.too_large";
    /** Top-level 413: more files in one upload than allowed. */
    public static final String TACHO_TOO_MANY = "tacho.file.too_many";

    /** Finance (per-field): invoice number taken in the direction, payment above the open amount, no e-mail. */
    public static final String INVOICE_NUMBER_TAKEN = "finance.invoice.number_taken";
    public static final String PAYMENT_TOO_MUCH = "finance.payment.too_much";
    public static final String REMINDER_NO_EMAIL = "finance.reminder.no_email";

    /** Fuel (per-field): the export is unreadable, too large or lacks columns; the card is already known. */
    public static final String FUEL_UNREADABLE = "fuel.import.unreadable";
    public static final String FUEL_TOO_LARGE = "fuel.import.too_large";
    public static final String FUEL_COLUMNS_MISSING = "fuel.import.columns_missing";
    public static final String FUEL_CARD_TAKEN = "fuel.card.number_taken";

    /** Public API: the firma has no AI access add-on (403), too many calls (429), too many tokens (per-field). */
    public static final String API_AI_ACCESS_REQUIRED = "api.ai_access_required";
    public static final String API_RATE_LIMITED = "api.rate_limited";
    public static final String API_TOKEN_LIMIT = "api.token.limit";

    /** Routing: no engine (503), engine or geocoder down (502), no truck route, address not found (per-field). */
    public static final String CALC_ROUTING_DISABLED = "calc.routing.disabled";
    public static final String CALC_ROUTING_FAILED = "calc.routing.failed";
    public static final String CALC_ROUTE_NOT_FOUND = "calc.route.not_found";
    public static final String CALC_GEOCODE_NOT_FOUND = "calc.geocode.not_found";
    public static final String CALC_GEOCODE_FAILED = "calc.geocode.failed";

    /** Trips and orders (per-field): the VAT id or the number is already used in the firma. */
    public static final String CONTRACTOR_VAT_TAKEN = "trip.contractor.vat_taken";
    public static final String ORDER_NUMBER_TAKEN = "trip.order.number_taken";
    public static final String TRIP_NUMBER_TAKEN = "trip.trip.number_taken";
    /** Trip files: the trip is DONE or CANCELLED (409), not an image or PDF, larger than 15 MB (per-field). */
    public static final String TRIP_FILE_TRIP_CLOSED = "trip.file.trip_closed";
    public static final String TRIP_FILE_TYPE = "trip.file.type";
    public static final String TRIP_FILE_TOO_LARGE = "trip.file.too_large";
    /** CMR (per-field): the number is already used in the firma. */
    public static final String CMR_NUMBER_TAKEN = "trip.cmr.number_taken";

    private ErrorCodes() {
    }
}

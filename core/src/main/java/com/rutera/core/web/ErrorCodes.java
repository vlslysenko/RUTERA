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

    private ErrorCodes() {
    }
}

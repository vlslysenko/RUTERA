package com.rutera.core.web;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Business error with an HTTP status and an i18n code; turned into an {@link ErrorBody} by
 * {@link ApiExceptionHandler}. Services throw it instead of building responses themselves.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Map<String, String> details;

    public ApiException(HttpStatus status, String code) {
        this(status, code, null);
    }

    public ApiException(HttpStatus status, String code, Map<String, String> details) {
        super(status.value() + " " + code);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static ApiException notFound(String code) {
        return new ApiException(HttpStatus.NOT_FOUND, code);
    }

    public static ApiException badRequest(String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, code);
    }

    public static ApiException unauthorized(String code) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code);
    }

    public static ApiException forbidden(String code) {
        return new ApiException(HttpStatus.FORBIDDEN, code);
    }

    /** 422 with per-field codes, e.g. {@code {"language": "validation.invalid_value"}}. */
    public static ApiException validation(Map<String, String> fieldCodes) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.VALIDATION, fieldCodes);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, String> getDetails() {
        return details;
    }
}

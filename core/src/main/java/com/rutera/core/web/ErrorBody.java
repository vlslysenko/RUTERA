package com.rutera.core.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Body of every 4xx/5xx answer of the internal API, matching {@code ApiErrorBody} in the frontend.
 *
 * @param code    i18n key, e.g. {@code auth.login.invalid}, {@code error.not_found}; the client translates it
 * @param message the same text in the language of the request (convenience for logs and tools)
 * @param details field name to i18n key for validation errors; omitted otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(String code, String message, Map<String, String> details) {

    public static ErrorBody of(String code, String message) {
        return new ErrorBody(code, message, null);
    }
}

package com.rutera.core.web;

import com.rutera.common.i18n.LangService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns exceptions of controllers and services into the {@link ErrorBody} contract of the frontend.
 * The {@code message} is translated in the language of the request; validation details carry one i18n key
 * per field. Unexpected errors are logged with the request id (MDC) and answered as {@code error.internal}.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final LangService lang;

    public ApiExceptionHandler(LangService lang) {
        this.lang = lang;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> api(ApiException ex) {
        return respond(ex.getStatus(), ex.getCode(), ex.getDetails());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorBody> unauthenticated(AuthenticationException ex) {
        return respond(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorBody> denied(AccessDeniedException ex) {
        String code = ex instanceof CsrfException ? ErrorCodes.CSRF_INVALID : ErrorCodes.ACCESS_DENIED;
        return respond(HttpStatus.FORBIDDEN, code, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> invalidBody(MethodArgumentNotValidException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            details.putIfAbsent(error.getField(), fieldCode(error.getCode()));
        }
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.VALIDATION, details);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorBody> invalidArguments(HandlerMethodValidationException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(error -> {
            String field = error instanceof FieldError fe ? fe.getField() : result.getMethodParameter().getParameterName();
            details.putIfAbsent(field == null ? "?" : field, fieldCode(error.getCodes() == null || error.getCodes().length == 0
                    ? null : error.getCodes()[error.getCodes().length - 1]));
        }));
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.VALIDATION, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorBody> constraintViolation(ConstraintViolationException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String path = violation.getPropertyPath() == null ? "?" : violation.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            details.putIfAbsent(field, fieldCode(violation.getConstraintDescriptor() == null ? null
                    : violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()));
        }
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.VALIDATION, details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorBody> badRequest(Exception ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> notFound(NoResourceFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorBody> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ErrorCodes.METHOD_NOT_ALLOWED, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorBody> unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCodes.BAD_REQUEST, null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorBody> conflict(OptimisticLockingFailureException ex) {
        return respond(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception ex) {
        log.error("Unhandled error: {}", ex.toString(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL, null);
    }

    private ResponseEntity<ErrorBody> respond(HttpStatus status, String code, Map<String, String> details) {
        return ResponseEntity.status(status)
                .body(new ErrorBody(code, lang.get(code), details == null || details.isEmpty() ? null : details));
    }

    /** Maps a Bean Validation constraint name to one of the {@code validation.*} keys. */
    static String fieldCode(String constraint) {
        if (constraint == null) {
            return ErrorCodes.FIELD_INVALID_VALUE;
        }
        return switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty" -> ErrorCodes.FIELD_REQUIRED;
            case "Size", "Length", "Max", "DecimalMax" -> ErrorCodes.FIELD_TOO_LONG;
            case "Pattern", "Email", "Digits" -> ErrorCodes.FIELD_INVALID_FORMAT;
            default -> ErrorCodes.FIELD_INVALID_VALUE;
        };
    }
}

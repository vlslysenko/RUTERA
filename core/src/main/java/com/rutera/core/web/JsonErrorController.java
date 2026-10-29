package com.rutera.core.web;

import com.rutera.common.i18n.LangService;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Boot's whitelabel {@code /error} page, which receives errors raised outside Spring MVC (servlet
 * container, filters). Answers the same {@link ErrorBody} JSON as {@link ApiExceptionHandler}.
 */
@RestController
public class JsonErrorController implements ErrorController {

    private final LangService lang;

    public JsonErrorController(LangService lang) {
        this.lang = lang;
    }

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ErrorBody> error(HttpServletRequest request) {
        HttpStatus status = statusOf(request);
        String code = switch (status) {
            case UNAUTHORIZED -> ErrorCodes.UNAUTHENTICATED;
            case FORBIDDEN -> ErrorCodes.ACCESS_DENIED;
            case NOT_FOUND -> ErrorCodes.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> ErrorCodes.METHOD_NOT_ALLOWED;
            case BAD_REQUEST, UNSUPPORTED_MEDIA_TYPE, PAYLOAD_TOO_LARGE, URI_TOO_LONG -> ErrorCodes.BAD_REQUEST;
            default -> ErrorCodes.INTERNAL;
        };
        return ResponseEntity.status(status).body(ErrorBody.of(code, lang.get(code)));
    }

    private static HttpStatus statusOf(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (value instanceof Integer statusCode) {
            HttpStatus status = HttpStatus.resolve(statusCode);
            if (status != null) {
                return status;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}

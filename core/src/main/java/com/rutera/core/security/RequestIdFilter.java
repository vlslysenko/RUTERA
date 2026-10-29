package com.rutera.core.security;

import com.rutera.common.context.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Outermost filter of every request: assigns the request id, publishes it in the MDC ({@code req=} in the log
 * lines) and the {@code X-Request-Id} response header, and clears {@link RequestContext} and MDC when the
 * request is over. The firma and user are added later by {@link SessionUserFilter}, once the security context
 * has been loaded from the session.
 * <p>
 * An incoming {@code X-Request-Id} (set by a reverse proxy or ingress) is kept when it looks like an id;
 * anything else is replaced by a fresh UUID so that the value always fits {@code audit_log.request_id}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_FIRMA_ID = "firmaId";
    public static final String MDC_USER_ID = "userId";

    private static final String REQUEST_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    private static final Pattern ID_SHAPE = Pattern.compile("[A-Za-z0-9._-]{8,36}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = requestIdOf(request);
        RequestContext.clear();
        RequestContext.setRequestId(requestId);
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_FIRMA_ID);
            MDC.remove(MDC_USER_ID);
        }
    }

    private static String requestIdOf(HttpServletRequest request) {
        // Error and async dispatches of the same request keep the id of the original pass.
        if (request.getAttribute(REQUEST_ID_ATTRIBUTE) instanceof String existing) {
            return existing;
        }
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        String id = incoming != null && ID_SHAPE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, id);
        return id;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}

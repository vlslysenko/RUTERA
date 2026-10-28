package com.rutera.common.context;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Per-thread context of the current unit of work: tenant (firma), acting user and request id.
 * <p>
 * Set by the web layer after authentication (and by background jobs explicitly), read by the Hibernate
 * tenant resolver, JPA auditing and the audit log. Always cleared in a {@code finally} block; the web layer
 * does that in its outermost filter.
 * <p>
 * Tenant semantics (see docs/DB_SCHEMA.md, section 0):
 * <ul>
 *   <li>firma set: all {@code @TenantId} entities are filtered to that firma;</li>
 *   <li>root (service superuser, system jobs that explicitly ask for it): no tenant filter;</li>
 *   <li>nothing set: fail closed, queries on tenant entities return no rows.</li>
 * </ul>
 */
public final class RequestContext {

    private static final ThreadLocal<State> HOLDER = new ThreadLocal<>();

    private record State(Long firmaId, boolean root, Long userId, String requestId) {
    }

    private RequestContext() {
    }

    /** Scopes the thread to one firma (the normal case for a signed-in user of a company). */
    public static void setFirma(Long firmaId, Long userId) {
        State s = state();
        HOLDER.set(new State(firmaId, false, userId, s == null ? null : s.requestId()));
    }

    /** Root scope: no tenant filter. Only for the service superuser and explicit system jobs. */
    public static void setRoot(Long userId) {
        State s = state();
        HOLDER.set(new State(null, true, userId, s == null ? null : s.requestId()));
    }

    public static void setRequestId(String requestId) {
        State s = state();
        HOLDER.set(s == null ? new State(null, false, null, requestId)
                : new State(s.firmaId(), s.root(), s.userId(), requestId));
    }

    /** Drops tenant and user but keeps the request id (used when a request switches between scopes). */
    public static void clearTenant() {
        State s = state();
        if (s != null) {
            HOLDER.set(new State(null, false, null, s.requestId()));
        }
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Optional<Long> firmaId() {
        State s = state();
        return s == null ? Optional.empty() : Optional.ofNullable(s.firmaId());
    }

    public static boolean isRoot() {
        State s = state();
        return s != null && s.root();
    }

    public static Optional<Long> userId() {
        State s = state();
        return s == null ? Optional.empty() : Optional.ofNullable(s.userId());
    }

    public static Optional<String> requestId() {
        State s = state();
        return s == null ? Optional.empty() : Optional.ofNullable(s.requestId());
    }

    /** The firma of the current scope, or an exception when the thread has no tenant. */
    public static Long requireFirmaId() {
        return firmaId().orElseThrow(() -> new IllegalStateException("No firma in the current request context"));
    }

    /** Runs the work in the scope of one firma and restores the previous scope afterwards. */
    public static <T> T runAsFirma(Long firmaId, Long userId, Callable<T> work) throws Exception {
        State previous = state();
        setFirma(firmaId, userId);
        try {
            return work.call();
        } finally {
            restore(previous);
        }
    }

    /** Runs the work in root scope (no tenant filter) and restores the previous scope afterwards. */
    public static <T> T runAsRoot(Long userId, Callable<T> work) throws Exception {
        State previous = state();
        setRoot(userId);
        try {
            return work.call();
        } finally {
            restore(previous);
        }
    }

    private static void restore(State previous) {
        if (previous == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(previous);
        }
    }

    private static State state() {
        return HOLDER.get();
    }
}

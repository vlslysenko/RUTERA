package com.rutera.core.security;

import com.rutera.common.security.RequiresPrivilege;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Enforces {@link RequiresPrivilege} on Spring beans (services and controllers). Runs before
 * {@code @Transactional} so that a denied call never opens a transaction.
 * <p>
 * Outcomes: no signed-in user: {@code AuthenticationCredentialsNotFoundException} (401); missing key:
 * {@link AccessDeniedException} (403 {@code auth.access_denied}); the superuser always passes.
 */
@Aspect
@Component
@Order(0)
public class RequiresPrivilegeAspect {

    private static final Logger log = LoggerFactory.getLogger(RequiresPrivilegeAspect.class);

    @Around("@annotation(com.rutera.common.security.RequiresPrivilege)"
            + " || @within(com.rutera.common.security.RequiresPrivilege)")
    public Object check(ProceedingJoinPoint pjp) throws Throwable {
        RequiresPrivilege required = find(pjp);
        if (required != null) {
            AuthenticatedUser user = CurrentUser.require();
            if (!allowed(user, required)) {
                log.info("Access denied: user {} lacks {} for {}", user.id(), Arrays.toString(required.value()),
                        pjp.getSignature().toShortString());
                throw new AccessDeniedException("Missing privilege " + Arrays.toString(required.value()));
            }
        }
        return pjp.proceed();
    }

    static boolean allowed(AuthenticatedUser user, RequiresPrivilege required) {
        if (user.isSuperuser()) {
            return true;
        }
        String[] keys = required.value();
        if (keys.length == 0) {
            return true;
        }
        if (required.all()) {
            return Arrays.stream(keys).allMatch(user::hasPrivilege);
        }
        return Arrays.stream(keys).anyMatch(user::hasPrivilege);
    }

    private static RequiresPrivilege find(ProceedingJoinPoint pjp) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RequiresPrivilege onMethod = AnnotatedElementUtils.findMergedAnnotation(method, RequiresPrivilege.class);
        if (onMethod != null) {
            return onMethod;
        }
        Class<?> targetClass = pjp.getTarget() != null ? pjp.getTarget().getClass() : method.getDeclaringClass();
        return AnnotatedElementUtils.findMergedAnnotation(targetClass, RequiresPrivilege.class);
    }
}

package com.rutera.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which privilege key(s) from {@link Privileges} the signed-in user needs to call a service or
 * controller method. Checked by an aspect in the core module (RequiresPrivilegeAspect):
 * <ul>
 *   <li>no authenticated user: 401 {@code auth.unauthenticated};</li>
 *   <li>user without the key: 403 {@code auth.access_denied};</li>
 *   <li>the service superuser bypasses the check.</li>
 * </ul>
 * On a class the annotation applies to every public method; a method-level annotation wins.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPrivilege {

    /** One or more privilege keys, e.g. {@code Privileges.FLEET_VEHICLE_VIEW}. */
    String[] value();

    /** {@code false} (default): any of the keys is enough; {@code true}: all keys are required. */
    boolean all() default false;
}

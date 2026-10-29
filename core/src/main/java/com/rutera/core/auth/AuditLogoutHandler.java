package com.rutera.core.auth;

import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/** Writes the {@code auth/logout} audit entry; runs inside Spring Security's logout filter. */
@Component
public class AuditLogoutHandler implements LogoutHandler {

    private final AuditService audit;

    public AuditLogoutHandler(AuditService audit) {
        this.audit = audit;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            audit.record(AuditEvent.of(AuthService.MODULE, "logout", "Signed out").entity("app_user", user.id()),
                    user.firmaId(), user.id());
        }
    }
}

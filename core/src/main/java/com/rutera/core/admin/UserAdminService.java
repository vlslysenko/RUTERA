package com.rutera.core.admin;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.CustomRole;
import com.rutera.common.i18n.SupportedLanguages;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.CustomRoleRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.common.security.Role;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.security.AuthenticatedUser;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.user.PasswordService;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Users of the signed-in administrator's firma ({@code /app/admin/users}). {@code app_user} has no automatic
 * tenant filter, so every lookup checks the firma explicitly and answers 404 for users of other firmas.
 * <p>
 * Hierarchy as in SMDP ({@code hpoziom}): a user may only manage users whose role level is greater than or
 * equal to their own, and may only assign such roles ({@link Role#getLevel()}; a custom role carries its own
 * level). Nobody changes their own role or status or deletes themselves, so a firma cannot lose its last
 * owner by accident. Every change is written to the audit log.
 */
@Service
public class UserAdminService {

    public static final String MODULE = "admin";

    /** Roles an administrator can assign; SUPERUSER and API accounts are created elsewhere. */
    private static final Set<Role> ASSIGNABLE = EnumSet.of(Role.OWNER, Role.DISPATCHER, Role.LOGISTICIAN,
            Role.ACCOUNTANT, Role.DRIVER, Role.CUSTOM);
    private static final Pattern LOGIN_SHAPE = Pattern.compile("[\\p{L}\\p{N}._@+-]{3,254}");

    private final AppUserRepository users;
    private final CustomRoleRepository customRoles;
    private final FirmaRepository firmas;
    private final PasswordService passwords;
    private final AuditService audit;

    public UserAdminService(AppUserRepository users, CustomRoleRepository customRoles, FirmaRepository firmas,
                            PasswordService passwords, AuditService audit) {
        this.users = users;
        this.customRoles = customRoles;
        this.firmas = firmas;
        this.passwords = passwords;
        this.audit = audit;
    }

    @RequiresPrivilege(Privileges.ADMIN_USER_VIEW)
    @Transactional(readOnly = true)
    public List<AdminUserDto> list() {
        Instant now = Instant.now();
        return users.findByFirmaIdAndDeletedFalseOrderByLastNameAscFirstNameAsc(actorFirmaId()).stream()
                .map(user -> AdminUserDto.of(user, now))
                .toList();
    }

    @RequiresPrivilege(Privileges.ADMIN_USER_VIEW)
    @Transactional(readOnly = true)
    public AdminUserDto get(Long id) {
        return AdminUserDto.of(load(id), Instant.now());
    }

    @RequiresPrivilege(Privileges.ADMIN_USER_MANAGE)
    @Transactional
    public AdminUserDto create(CreateUserRequest request) {
        AuthenticatedUser actor = CurrentUser.require();
        Long firmaId = actorFirmaId();
        Map<String, String> errors = new LinkedHashMap<>();
        String login = normalizeLogin(request.login(), errors);
        Role role = parseRole(request.role(), errors);
        Long customRoleId = checkCustomRole(role, request.customRoleId(), errors);
        String language = readLanguage(request.language(), firmaLanguage(firmaId), errors);
        passwords.check("password", request.password(), errors);
        if (login != null && users.existsByLogin(login)) {
            errors.put("login", ErrorCodes.USER_LOGIN_TAKEN);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        requireLevel(actorLevel(actor), levelOf(role, customRoleId));

        AppUser user = new AppUser();
        user.setFirmaId(firmaId);
        user.setLogin(login);
        user.setRole(role);
        user.setCustomRoleId(customRoleId);
        user.setFirstName(request.firstName().trim());
        user.setLastName(blankToEmpty(request.lastName()));
        user.setPhone(blankToNull(request.phone()));
        user.setLang(language);
        user.setActive(true);
        passwords.apply(user, request.password());
        user = users.save(user);

        audit.record(AuditEvent.of(MODULE, "user_created", "Created user " + user.getLogin())
                .entity("app_user", user.getId())
                .detail("login", user.getLogin())
                .detail("role", user.getRole().name()));
        return AdminUserDto.of(user, Instant.now());
    }

    @RequiresPrivilege(Privileges.ADMIN_USER_MANAGE)
    @Transactional
    public AdminUserDto update(Long id, UpdateUserRequest request) {
        AuthenticatedUser actor = CurrentUser.require();
        AppUser target = load(id);
        Map<String, String> errors = new LinkedHashMap<>();
        Role role = parseRole(request.role(), errors);
        Long customRoleId = checkCustomRole(role, request.customRoleId(), errors);
        String language = readLanguage(request.language(), null, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        boolean self = target.getId().equals(actor.id());
        boolean roleChange = role != target.getRole() || !Objects.equals(customRoleId, target.getCustomRoleId());
        if (self) {
            if (roleChange || !request.active()) {
                throw ApiException.forbidden(ErrorCodes.USER_SELF_FORBIDDEN);
            }
        } else {
            int actorLevel = actorLevel(actor);
            requireLevel(actorLevel, levelOf(target));
            requireLevel(actorLevel, levelOf(role, customRoleId));
        }

        Map<String, Object> changes = new LinkedHashMap<>();
        if (!request.firstName().trim().equals(target.getFirstName())) {
            target.setFirstName(request.firstName().trim());
            changes.put("firstName", target.getFirstName());
        }
        String lastName = blankToEmpty(request.lastName());
        if (!lastName.equals(target.getLastName())) {
            target.setLastName(lastName);
            changes.put("lastName", lastName);
        }
        if (roleChange) {
            changes.put("role", target.getRole().name() + " -> " + role.name());
            target.setRole(role);
            target.setCustomRoleId(customRoleId);
        }
        String phone = blankToNull(request.phone());
        if (!Objects.equals(phone, target.getPhone())) {
            target.setPhone(phone);
            changes.put("phone", phone == null ? "" : phone);
        }
        if (!language.equals(target.getLang())) {
            target.setLang(language);
            changes.put("language", language);
        }
        if (request.active() != target.isActive()) {
            target.setActive(request.active());
            changes.put("active", request.active());
        }
        if (!changes.isEmpty()) {
            target = users.save(target);
            AuditEvent event = AuditEvent.of(MODULE, "user_updated", "Updated user " + target.getLogin())
                    .entity("app_user", target.getId());
            for (Map.Entry<String, Object> change : changes.entrySet()) {
                event = event.detail(change.getKey(), change.getValue());
            }
            audit.record(event);
        }
        return AdminUserDto.of(target, Instant.now());
    }

    /** Sets a new password for another user (own password: {@code PUT /app/me/password}). */
    @RequiresPrivilege(Privileges.ADMIN_USER_MANAGE)
    @Transactional
    public void resetPassword(Long id, SetPasswordRequest request) {
        AuthenticatedUser actor = CurrentUser.require();
        AppUser target = load(id);
        if (target.getId().equals(actor.id())) {
            throw ApiException.forbidden(ErrorCodes.USER_SELF_FORBIDDEN);
        }
        requireLevel(actorLevel(actor), levelOf(target));
        Map<String, String> errors = new LinkedHashMap<>();
        passwords.check("newPassword", request.newPassword(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        passwords.apply(target, request.newPassword());
        users.save(target);
        audit.record(AuditEvent.of(MODULE, "user_password_reset", "Reset password of user " + target.getLogin())
                .entity("app_user", target.getId()));
    }

    /** Lifts a lockout before it expires and resets the failure counter. */
    @RequiresPrivilege(Privileges.ADMIN_USER_MANAGE)
    @Transactional
    public void unlock(Long id) {
        AuthenticatedUser actor = CurrentUser.require();
        AppUser target = load(id);
        requireLevel(actorLevel(actor), levelOf(target));
        target.setLockedUntil(null);
        target.setFailedLogins(0);
        users.save(target);
        audit.record(AuditEvent.of(MODULE, "user_unlocked", "Unlocked user " + target.getLogin())
                .entity("app_user", target.getId()));
    }

    /** Soft delete: the row stays for the audit trail and the login remains reserved. */
    @RequiresPrivilege(Privileges.ADMIN_USER_MANAGE)
    @Transactional
    public void delete(Long id) {
        AuthenticatedUser actor = CurrentUser.require();
        AppUser target = load(id);
        if (target.getId().equals(actor.id())) {
            throw ApiException.forbidden(ErrorCodes.USER_SELF_FORBIDDEN);
        }
        requireLevel(actorLevel(actor), levelOf(target));
        target.setDeleted(true);
        target.setActive(false);
        users.save(target);
        audit.record(AuditEvent.of(MODULE, "user_deleted", "Deleted user " + target.getLogin())
                .entity("app_user", target.getId())
                .detail("login", target.getLogin()));
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private AppUser load(Long id) {
        Long firmaId = actorFirmaId();
        return users.findById(id)
                .filter(user -> !user.isDeleted() && firmaId.equals(user.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    /** The administration screens are firma-scoped; the service superuser has no firma to administer here. */
    private static Long actorFirmaId() {
        Long firmaId = CurrentUser.require().firmaId();
        if (firmaId == null) {
            throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED);
        }
        return firmaId;
    }

    private int actorLevel(AuthenticatedUser actor) {
        if (actor.isSuperuser()) {
            return Role.SUPERUSER.getLevel();
        }
        Long customRoleId = users.findById(actor.id()).map(AppUser::getCustomRoleId).orElse(null);
        return levelOf(actor.role(), customRoleId);
    }

    private int levelOf(AppUser user) {
        return levelOf(user.getRole(), user.getCustomRoleId());
    }

    private int levelOf(Role role, Long customRoleId) {
        if (role == Role.CUSTOM && customRoleId != null) {
            return customRoles.findById(customRoleId).map(CustomRole::getLevel).orElse(Role.CUSTOM.getLevel());
        }
        return role.getLevel();
    }

    private static void requireLevel(int actorLevel, int targetLevel) {
        if (actorLevel > targetLevel) {
            throw ApiException.forbidden(ErrorCodes.USER_ROLE_NOT_ALLOWED);
        }
    }

    private static String normalizeLogin(String raw, Map<String, String> errors) {
        String login = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (login.isEmpty()) {
            errors.put("login", ErrorCodes.FIELD_REQUIRED);
            return null;
        }
        if (!LOGIN_SHAPE.matcher(login).matches()) {
            errors.put("login", ErrorCodes.FIELD_INVALID_FORMAT);
            return null;
        }
        return login;
    }

    private static Role parseRole(String raw, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) {
            errors.put("role", ErrorCodes.FIELD_REQUIRED);
            return null;
        }
        try {
            Role role = Role.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            if (ASSIGNABLE.contains(role)) {
                return role;
            }
        } catch (IllegalArgumentException ignored) {
            // falls through to the error below
        }
        errors.put("role", ErrorCodes.FIELD_INVALID_VALUE);
        return null;
    }

    /** CUSTOM needs an existing custom role of the firma (the repository is tenant-filtered); others ignore it. */
    private Long checkCustomRole(Role role, Long customRoleId, Map<String, String> errors) {
        if (role != Role.CUSTOM) {
            return null;
        }
        if (customRoleId == null) {
            errors.put("customRoleId", ErrorCodes.FIELD_REQUIRED);
            return null;
        }
        if (customRoles.findById(customRoleId).isEmpty()) {
            errors.put("customRoleId", ErrorCodes.FIELD_INVALID_VALUE);
            return null;
        }
        return customRoleId;
    }

    private static String readLanguage(String raw, String fallback, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) {
            if (fallback != null) {
                return fallback;
            }
            errors.put("language", ErrorCodes.FIELD_REQUIRED);
            return null;
        }
        String code = raw.trim().toLowerCase(Locale.ROOT);
        if (!SupportedLanguages.isSupported(code)) {
            errors.put("language", ErrorCodes.FIELD_INVALID_VALUE);
            return null;
        }
        return code;
    }

    private String firmaLanguage(Long firmaId) {
        return firmas.findById(firmaId).map(f -> SupportedLanguages.normalize(f.getLang()))
                .orElse(SupportedLanguages.DEFAULT);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

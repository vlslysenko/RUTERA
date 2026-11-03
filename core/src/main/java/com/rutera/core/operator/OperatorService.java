package com.rutera.core.operator;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.i18n.SupportedLanguages;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.repository.VehicleRepository;
import com.rutera.common.security.Role;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.bootstrap.BootstrapRunner;
import com.rutera.core.security.AuthenticatedUser;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.user.PasswordService;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Operator console of the cloud installation ({@code /app/operator/firmas}): the paid options and the state of
 * every firma. Only for the role SUPERUSER (logins without a firma, root scope); everyone else gets 403. A firma
 * that is not active or whose {@code expiresAt} has passed cannot sign in ({@code SessionUserService}). In a
 * standalone installation the environment values (RUTERA_AI_ACCESS, RUTERA_PLAN, RUTERA_VEHICLE_LIMIT) are written
 * again at every start when set.
 */
@Service
public class OperatorService {

    public static final String MODULE = "operator";
    private static final Pattern PLAN = Pattern.compile("[A-Z0-9_]{1,32}");
    private static final Pattern SHORT_CODE = Pattern.compile("[A-Z0-9_-]{2,32}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern LOGIN_SHAPE = Pattern.compile("[\\p{L}\\p{N}._@+-]{3,254}");
    static final int MAX_VEHICLE_LIMIT = 100_000;

    private final FirmaRepository firmas;
    private final AppUserRepository users;
    private final VehicleRepository vehicles;
    private final AuditService audit;
    private final PasswordService passwords;

    public OperatorService(FirmaRepository firmas, AppUserRepository users, VehicleRepository vehicles,
                           AuditService audit, PasswordService passwords) {
        this.firmas = firmas;
        this.users = users;
        this.vehicles = vehicles;
        this.audit = audit;
        this.passwords = passwords;
    }

    public record FirmaItem(Long id, String name, String shortCode, String country, String plan, int vehicleLimit,
                            boolean aiAccess, boolean active, boolean test, Instant expiresAt, long users,
                            long vehicles, Instant createdAt) {
    }

    public record FirmaPage(List<FirmaItem> items, long total) {
    }

    /** Body of {@code PUT /app/operator/firmas/{id}}: all operator fields; {@code expiresAt} null = no end. */
    public record FirmaInput(String plan, Integer vehicleLimit, Boolean aiAccess, Boolean active, Instant expiresAt) {
    }

    /** The first owner of a new firma; {@code password} empty = generated and returned once. */
    public record OwnerInput(String login, String firstName, String lastName, String password) {
    }

    /** Body of {@code POST /app/operator/firmas}. */
    public record NewFirma(String name, String shortCode, String country, String lang, String timezone,
                           String baseCurrency, String plan, Integer vehicleLimit, Boolean aiAccess, Boolean test,
                           Instant expiresAt, OwnerInput owner) {
    }

    /** {@code password}: the generated owner password, only when none was given; shown this once. */
    public record Created(FirmaItem firma, Long ownerId, String ownerLogin, String password) {
    }

    @Transactional(readOnly = true)
    public FirmaPage list(String q, int page, int size) {
        requireOperator();
        if (page < 0 || size < 1 || size > 200) {
            throw ApiException.validation(Map.of(page < 0 ? "page" : "size", ErrorCodes.FIELD_INVALID_VALUE));
        }
        String filter = InputFields.blankToNull(q);
        Page<Firma> result = firmas.search(filter == null ? null : "%" + filter.toLowerCase(Locale.ROOT) + "%",
                PageRequest.of(page, size, Sort.by("id")));
        Map<Long, Long> userCounts = counts(users.countActivePerFirma());
        Map<Long, Long> vehicleCounts = counts(vehicles.countPerFirma());
        return new FirmaPage(result.getContent().stream().map(f -> toItem(f, userCounts, vehicleCounts)).toList(),
                result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public FirmaItem get(Long id) {
        requireOperator();
        return toItem(load(id), counts(users.countActivePerFirma()), counts(vehicles.countPerFirma()));
    }

    @Transactional
    public FirmaItem update(Long id, FirmaInput input) {
        AuthenticatedUser operator = requireOperator();
        Firma f = load(id);
        Map<String, String> errors = new HashMap<>();
        String plan = InputFields.blankToNull(input.plan());
        plan = plan == null ? null : plan.toUpperCase(Locale.ROOT);
        if (plan == null) {
            errors.put("plan", ErrorCodes.FIELD_REQUIRED);
        } else if (!PLAN.matcher(plan).matches()) {
            errors.put("plan", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (input.vehicleLimit() == null) {
            errors.put("vehicleLimit", ErrorCodes.FIELD_REQUIRED);
        } else if (input.vehicleLimit() < 0 || input.vehicleLimit() > MAX_VEHICLE_LIMIT) {
            errors.put("vehicleLimit", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (input.aiAccess() == null) {
            errors.put("aiAccess", ErrorCodes.FIELD_REQUIRED);
        }
        if (input.active() == null) {
            errors.put("active", ErrorCodes.FIELD_REQUIRED);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        String before = describe(f);
        f.setPlan(plan);
        f.setVehicleLimit(input.vehicleLimit());
        f.setAiAccess(input.aiAccess());
        f.setActive(input.active());
        f.setExpiresAt(input.expiresAt());
        f = firmas.save(f);
        String after = describe(f);
        if (!before.equals(after)) {
            audit.record(AuditEvent.of(MODULE, "firma_options_changed", "Operator changed " + before + " -> " + after)
                    .entity("firma", f.getId()), f.getId(), operator.id());
        }
        return toItem(f, counts(users.countActivePerFirma()), counts(vehicles.countPerFirma()));
    }

    /** Onboarding: a new firma with its first OWNER login. */
    @Transactional
    public Created create(NewFirma in) {
        AuthenticatedUser operator = requireOperator();
        Map<String, String> errors = new LinkedHashMap<>();
        String name = InputFields.blankToNull(in.name());
        if (name == null) {
            errors.put("name", ErrorCodes.FIELD_REQUIRED);
        } else if (name.length() > 200) {
            errors.put("name", ErrorCodes.FIELD_TOO_LONG);
        }
        String shortCode = InputFields.blankToNull(in.shortCode());
        shortCode = shortCode == null ? null : shortCode.toUpperCase(Locale.ROOT);
        if (shortCode == null) {
            errors.put("shortCode", ErrorCodes.FIELD_REQUIRED);
        } else if (!SHORT_CODE.matcher(shortCode).matches()) {
            errors.put("shortCode", ErrorCodes.FIELD_INVALID_FORMAT);
        } else if (firmas.findByShortCode(shortCode).isPresent()) {
            errors.put("shortCode", ErrorCodes.OPERATOR_SHORT_CODE_TAKEN);
        }
        String country = InputFields.country("country", in.country(), errors);
        if (country == null && !errors.containsKey("country")) {
            errors.put("country", ErrorCodes.FIELD_REQUIRED);
        }
        String lang = InputFields.blankToNull(in.lang());
        lang = lang == null ? SupportedLanguages.DEFAULT : lang.toLowerCase(Locale.ROOT);
        if (!SupportedLanguages.isSupported(lang)) {
            errors.put("lang", ErrorCodes.FIELD_INVALID_VALUE);
        }
        String zone = InputFields.blankToNull(in.timezone());
        zone = zone == null ? "UTC" : zone;
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            errors.put("timezone", ErrorCodes.FIELD_INVALID_VALUE);
        }
        String currency = InputFields.blankToNull(in.baseCurrency());
        currency = currency == null ? "EUR" : currency.toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(currency).matches()) {
            errors.put("baseCurrency", ErrorCodes.FIELD_INVALID_FORMAT);
        }
        String plan = InputFields.blankToNull(in.plan());
        plan = plan == null ? "STANDARD" : plan.toUpperCase(Locale.ROOT);
        if (!PLAN.matcher(plan).matches()) {
            errors.put("plan", ErrorCodes.FIELD_INVALID_VALUE);
        }
        int limit = in.vehicleLimit() == null ? 10 : in.vehicleLimit();
        if (limit < 0 || limit > MAX_VEHICLE_LIMIT) {
            errors.put("vehicleLimit", ErrorCodes.FIELD_INVALID_VALUE);
        }
        OwnerInput owner = in.owner();
        String login = null;
        String first = null;
        String last = null;
        String password = null;
        if (owner == null) {
            errors.put("owner.login", ErrorCodes.FIELD_REQUIRED);
            errors.put("owner.firstName", ErrorCodes.FIELD_REQUIRED);
        } else {
            login = owner.login() == null ? "" : owner.login().strip().toLowerCase(Locale.ROOT);
            if (login.isEmpty()) {
                errors.put("owner.login", ErrorCodes.FIELD_REQUIRED);
            } else if (!LOGIN_SHAPE.matcher(login).matches()) {
                errors.put("owner.login", ErrorCodes.FIELD_INVALID_FORMAT);
            } else if (users.existsByLogin(login)) {
                errors.put("owner.login", ErrorCodes.USER_LOGIN_TAKEN);
            }
            first = InputFields.blankToNull(owner.firstName());
            if (first == null) {
                errors.put("owner.firstName", ErrorCodes.FIELD_REQUIRED);
            } else if (first.length() > 100) {
                errors.put("owner.firstName", ErrorCodes.FIELD_TOO_LONG);
            }
            last = InputFields.blankToNull(owner.lastName());
            if (last != null && last.length() > 100) {
                errors.put("owner.lastName", ErrorCodes.FIELD_TOO_LONG);
            }
            password = InputFields.blankToNull(owner.password());
            if (password != null) {
                passwords.check("owner.password", password, errors);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }

        Firma f = new Firma();
        f.setName(name);
        f.setShortCode(shortCode);
        f.setCountry(country);
        f.setLang(lang);
        f.setTimezone(zone);
        f.setBaseCurrency(currency);
        f.setPlan(plan);
        f.setVehicleLimit(limit);
        f.setAiAccess(Boolean.TRUE.equals(in.aiAccess()));
        f.setTest(Boolean.TRUE.equals(in.test()));
        f.setExpiresAt(in.expiresAt());
        f = firmas.save(f);

        boolean generated = password == null;
        String secret = generated ? BootstrapRunner.generatePassword() : password;
        AppUser user = new AppUser();
        user.setFirmaId(f.getId());
        user.setLogin(login);
        user.setRole(Role.OWNER);
        user.setFirstName(first);
        user.setLastName(last == null ? "" : last);
        user.setLang(lang);
        user.setActive(true);
        passwords.apply(user, secret);
        user = users.save(user);

        audit.record(AuditEvent.of(MODULE, "firma_created", "Operator created the company " + f.getName()
                        + " with owner " + user.getLogin()).entity("firma", f.getId()).detail("plan", f.getPlan()),
                f.getId(), operator.id());
        FirmaItem item = toItem(f, Map.of(f.getId(), 1L), Map.of());
        return new Created(item, user.getId(), user.getLogin(), generated ? secret : null);
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private static AuthenticatedUser requireOperator() {
        AuthenticatedUser user = CurrentUser.require();
        if (user.role() != Role.SUPERUSER) {
            throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED);
        }
        return user;
    }

    private Firma load(Long id) {
        return firmas.findById(id).orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private static Map<Long, Long> counts(List<Object[]> rows) {
        Map<Long, Long> m = new HashMap<>();
        for (Object[] row : rows) {
            m.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return m;
    }

    private static String describe(Firma f) {
        return "plan " + f.getPlan() + ", vehicle limit " + f.getVehicleLimit() + ", AI access " + f.isAiAccess()
                + ", active " + f.isActive() + ", expires " + Objects.toString(f.getExpiresAt(), "never");
    }

    private static FirmaItem toItem(Firma f, Map<Long, Long> userCounts, Map<Long, Long> vehicleCounts) {
        return new FirmaItem(f.getId(), f.getName(), f.getShortCode(), f.getCountry(), f.getPlan(),
                f.getVehicleLimit(), f.isAiAccess(), f.isActive(), f.isTest(), f.getExpiresAt(),
                userCounts.getOrDefault(f.getId(), 0L), vehicleCounts.getOrDefault(f.getId(), 0L), f.getCreatedAt());
    }
}

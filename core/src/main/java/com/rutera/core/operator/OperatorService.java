package com.rutera.core.operator;

import com.rutera.common.domain.Firma;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.repository.VehicleRepository;
import com.rutera.common.security.Role;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.security.AuthenticatedUser;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
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
    static final int MAX_VEHICLE_LIMIT = 100_000;

    private final FirmaRepository firmas;
    private final AppUserRepository users;
    private final VehicleRepository vehicles;
    private final AuditService audit;

    public OperatorService(FirmaRepository firmas, AppUserRepository users, VehicleRepository vehicles,
                           AuditService audit) {
        this.firmas = firmas;
        this.users = users;
        this.vehicles = vehicles;
        this.audit = audit;
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

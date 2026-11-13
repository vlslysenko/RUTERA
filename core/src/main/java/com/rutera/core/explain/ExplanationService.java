package com.rutera.core.explain;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Driver;
import com.rutera.common.domain.DriverCard;
import com.rutera.common.domain.DriverExplanation;
import com.rutera.common.domain.DriverViolation;
import com.rutera.common.domain.Firma;
import com.rutera.common.domain.StoredFile;
import com.rutera.common.domain.Vehicle;
import com.rutera.common.i18n.LangService;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.DriverCardRepository;
import com.rutera.common.repository.DriverExplanationRepository;
import com.rutera.common.repository.DriverRepository;
import com.rutera.common.repository.DriverViolationRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.repository.StoredFileRepository;
import com.rutera.common.repository.VehicleRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.driver.DriverSelf;
import com.rutera.core.push.PushService;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.storage.FileStorage;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Drivers' written explanations of tachograph infringements ({@code /app/explanations}).
 * <ul>
 * <li>The import asks for one for every new violation of the last {@link ExplanationSettings#days()} days when the
 * firma has {@link ExplanationSettings#auto()} on; the office can ask for one from a violation or by hand.</li>
 * <li>The driver gets a push ({@code explanation.requested}), picks a reason, may add a text and a place, and signs
 * on the screen; the PDF (template in the language of the firma's country) is stored with the signature.</li>
 * <li>Office: {@code tacho.hos/view} reads, {@code tacho.violation/ack} asks, reminds and cancels. A driver
 * ({@code driver_app.self/use}) reads and signs only their own.</li>
 * </ul>
 */
@Service
public class ExplanationService {

    static final String MODULE = "tacho";
    static final String PUSH_TYPE = "explanation.requested";
    static final String ERROR_NOT_PENDING = "explanation.not_pending";
    private static final int MAX_SIGNATURE_BYTES = 300 * 1024;
    private static final String PNG_PREFIX = "data:image/png;base64,";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final DriverExplanationRepository explanations;
    private final DriverViolationRepository violations;
    private final DriverRepository drivers;
    private final DriverCardRepository cards;
    private final VehicleRepository vehicles;
    private final AppUserRepository users;
    private final FirmaRepository firmas;
    private final StoredFileRepository files;
    private final FileStorage storage;
    private final DriverSelf self;
    private final ExplanationSettings settings;
    private final ExplanationTemplates templates;
    private final ExplanationPdf pdf;
    private final PushService push;
    private final LangService lang;
    private final AuditService audit;

    public ExplanationService(DriverExplanationRepository explanations, DriverViolationRepository violations,
                              DriverRepository drivers, DriverCardRepository cards, VehicleRepository vehicles,
                              AppUserRepository users, FirmaRepository firmas, StoredFileRepository files,
                              FileStorage storage, DriverSelf self, ExplanationSettings settings,
                              ExplanationTemplates templates, ExplanationPdf pdf, PushService push, LangService lang,
                              AuditService audit) {
        this.explanations = explanations;
        this.violations = violations;
        this.drivers = drivers;
        this.cards = cards;
        this.vehicles = vehicles;
        this.users = users;
        this.firmas = firmas;
        this.files = files;
        this.storage = storage;
        this.self = self;
        this.settings = settings;
        this.templates = templates;
        this.pdf = pdf;
        this.push = push;
        this.lang = lang;
        this.audit = audit;
    }

    /** A PDF to send: its bytes and file name. */
    public record Pdf(byte[] bytes, String fileName) {
    }

    // --- reading -------------------------------------------------------------------------------------------

    @RequiresPrivilege({Privileges.TACHO_HOS_VIEW, Privileges.DRIVER_APP_SELF_USE})
    @Transactional(readOnly = true)
    public ExplanationDto.Page list(String status, Long driverId, LocalDate from, LocalDate to, int page, int size) {
        Long driver = self.full(Privileges.TACHO_HOS_VIEW) ? driverId : self.ownDriverId();
        DriverExplanation.Status s = status == null || status.isBlank() ? null
                : InputFields.parseEnum(DriverExplanation.Status.class, "status", status, false, new LinkedHashMap<>());
        if (status != null && !status.isBlank() && s == null) {
            throw ApiException.validation(Map.of("status", ErrorCodes.FIELD_INVALID_VALUE));
        }
        ZoneId zone = zone(firma());
        Instant fromAt = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant toAt = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        org.springframework.data.domain.Page<DriverExplanation> result = explanations.search(s, driver, fromAt, toAt,
                PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 100)));
        return new ExplanationDto.Page(toDtos(result.getContent()), result.getTotalElements());
    }

    @RequiresPrivilege({Privileges.TACHO_HOS_VIEW, Privileges.DRIVER_APP_SELF_USE})
    @Transactional(readOnly = true)
    public ExplanationDto.Explanation get(Long id) {
        return toDtos(List.of(visible(id))).getFirst();
    }

    /** The signed PDF, or for one not signed yet a preview marked as a draft. */
    @RequiresPrivilege({Privileges.TACHO_HOS_VIEW, Privileges.DRIVER_APP_SELF_USE})
    @Transactional(readOnly = true)
    public Pdf pdf(Long id) {
        DriverExplanation e = visible(id);
        String name = "explanation-" + e.getId() + ".pdf";
        if (e.getStatus() == DriverExplanation.Status.SIGNED && e.getPdfFileId() != null) {
            StoredFile file = files.findById(e.getPdfFileId())
                    .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
            return new Pdf(storage.read(file), name);
        }
        Firma firma = firma();
        return new Pdf(render(e, firma, templates.documentLanguage(firma.getCountry()), null, true), name);
    }

    /** The explanations of these violations (for the violation list). */
    @Transactional(readOnly = true)
    public Map<Long, DriverExplanation> ofViolations(Collection<Long> violationIds) {
        if (violationIds.isEmpty()) {
            return Map.of();
        }
        return explanations.findByViolationIdIn(violationIds).stream()
                .collect(Collectors.toMap(DriverExplanation::getViolationId, Function.identity(), (a, b) ->
                        a.getStatus() == DriverExplanation.Status.CANCELLED ? b : a));
    }

    // --- asking --------------------------------------------------------------------------------------------

    @RequiresPrivilege(Privileges.TACHO_VIOLATION_ACK)
    @Transactional
    public ExplanationDto.Explanation create(ExplanationDto.CreateInput in) {
        Firma firma = firma();
        DriverExplanation e;
        if (in.violationId() != null) {
            DriverViolation v = violations.findById(in.violationId())
                    .orElseThrow(() -> ApiException.validation(Map.of("violationId", ErrorCodes.FIELD_INVALID_VALUE)));
            e = forViolation(v, firma, DriverExplanation.Source.MANUAL);
        } else {
            e = manual(in, firma);
        }
        audit.record(AuditEvent.of(MODULE, "explanation_requested", "Asked a driver for an explanation")
                .entity("driver_explanation", e.getId()).detail("driverId", e.getDriverId())
                .detail("rule", e.getRuleCode()));
        return toDtos(List.of(e)).getFirst();
    }

    /**
     * Called by the import for a new violation: asks for an explanation when the firma has that on and the
     * infringement is recent enough. Runs in the firma's context inside the import's transaction.
     */
    public void onNewViolation(DriverViolation v) {
        if (!settings.auto() || v.getStartAt().isBefore(Instant.now().minus(Duration.ofDays(settings.days())))) {
            return;
        }
        Firma firma = firmas.findById(v.getFirmaId()).orElse(null);
        if (firma != null) {
            forViolation(v, firma, DriverExplanation.Source.AUTO);
        }
    }

    /** Clears the violation of explanations whose violations are deleted by a recomputation. */
    public void detach(Collection<Long> violationIds) {
        if (!violationIds.isEmpty()) {
            explanations.detachViolations(violationIds);
        }
    }

    @RequiresPrivilege(Privileges.TACHO_VIOLATION_ACK)
    @Transactional
    public ExplanationDto.Explanation remind(Long id) {
        DriverExplanation e = load(id);
        requirePending(e);
        notifyDriver(e, firma());
        return toDtos(List.of(e)).getFirst();
    }

    @RequiresPrivilege(Privileges.TACHO_VIOLATION_ACK)
    @Transactional
    public ExplanationDto.Explanation cancel(Long id) {
        DriverExplanation e = load(id);
        requirePending(e);
        e.setStatus(DriverExplanation.Status.CANCELLED);
        e.setCancelledAt(Instant.now());
        e.setCancelledBy(CurrentUser.require().id());
        explanations.save(e);
        audit.record(AuditEvent.of(MODULE, "explanation_cancelled", "Cancelled a driver explanation")
                .entity("driver_explanation", e.getId()).detail("driverId", e.getDriverId()));
        return toDtos(List.of(e)).getFirst();
    }

    // --- signing -------------------------------------------------------------------------------------------

    /** The driver's own explanation, signed: reason, optional text and place, the signature as a PNG data URL. */
    @RequiresPrivilege(Privileges.DRIVER_APP_SELF_USE)
    @Transactional
    public ExplanationDto.Explanation sign(Long id, ExplanationDto.SignInput in) {
        DriverExplanation e = load(id);
        self.requireOwn(e.getDriverId());
        requirePending(e);
        Map<String, String> errors = new LinkedHashMap<>();
        DriverExplanation.Reason reason = InputFields.parseEnum(DriverExplanation.Reason.class, "reason",
                in == null ? null : in.reason(), true, errors);
        String text = in == null ? null : InputFields.blankToNull(in.reasonText());
        if (reason == DriverExplanation.Reason.OTHER && text == null) {
            errors.put("reasonText", ErrorCodes.FIELD_REQUIRED);
        }
        if (text != null && text.length() > 1000) {
            errors.put("reasonText", ErrorCodes.FIELD_TOO_LONG);
        }
        String place = in == null ? null : InputFields.blankToNull(in.place());
        if (place != null && place.length() > 100) {
            errors.put("place", ErrorCodes.FIELD_TOO_LONG);
        }
        byte[] png = signature(in == null ? null : in.signature(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        Firma firma = firma();
        Long userId = CurrentUser.require().id();
        e.setReason(reason);
        e.setReasonText(text);
        e.setPlace(place);
        e.setSignedAt(Instant.now());
        e.setSignedBy(userId);
        e.setDocLang(templates.documentLanguage(firma.getCountry()));
        StoredFile signature = storage.store(firma.getId(), "SIGNATURE", "signature-" + e.getId() + ".png",
                "image/png", png, sha256(png), userId);
        byte[] bytes = render(e, firma, e.getDocLang(), png, false);
        StoredFile document = storage.store(firma.getId(), "EXPLANATION", "explanation-" + e.getId() + ".pdf",
                "application/pdf", bytes, sha256(bytes), userId);
        e.setSignatureFileId(signature.getId());
        e.setPdfFileId(document.getId());
        e.setStatus(DriverExplanation.Status.SIGNED);
        explanations.save(e);
        audit.record(AuditEvent.of(MODULE, "explanation_signed", "Signed a driver explanation")
                .entity("driver_explanation", e.getId()).detail("driverId", e.getDriverId())
                .detail("reason", reason.name()));
        return toDtos(List.of(e)).getFirst();
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private DriverExplanation forViolation(DriverViolation v, Firma firma, DriverExplanation.Source source) {
        Optional<DriverExplanation> existing = explanations.findByDriverIdAndRuleCodeAndStartedAt(v.getDriverId(),
                v.getRule().name(), v.getStartAt());
        if (existing.isPresent()) {
            DriverExplanation e = existing.get();
            boolean reopen = e.getStatus() == DriverExplanation.Status.CANCELLED
                    && source == DriverExplanation.Source.MANUAL;
            if (e.getViolationId() == null || reopen) {
                e.setViolationId(v.getId());
                if (reopen) {
                    e.setStatus(DriverExplanation.Status.PENDING);
                    e.setCancelledAt(null);
                    e.setCancelledBy(null);
                }
                explanations.save(e);
                if (reopen) {
                    notifyDriver(e, firma);
                }
            }
            return e;
        }
        DriverExplanation e = new DriverExplanation();
        e.setFirmaId(firma.getId());
        e.setDriverId(v.getDriverId());
        e.setViolationId(v.getId());
        e.setRuleCode(v.getRule().name());
        e.setVehiclePlate(v.getVehiclePlate());
        e.setStartedAt(v.getStartAt());
        e.setEndedAt(v.getEndAt());
        e.setActualMin(v.getActualValue());
        e.setLimitMin(v.getLimitValue());
        e.setExceededMin(Math.abs(v.getActualValue() - v.getLimitValue()));
        e.setStatus(DriverExplanation.Status.PENDING);
        e.setSource(source);
        e.setDocLang(templates.documentLanguage(firma.getCountry()));
        explanations.save(e);
        notifyDriver(e, firma);
        return e;
    }

    private DriverExplanation manual(ExplanationDto.CreateInput in, Firma firma) {
        Map<String, String> errors = new LinkedHashMap<>();
        Driver driver = in.driverId() == null ? null : drivers.findByIdAndDeletedFalse(in.driverId()).orElse(null);
        if (in.driverId() == null) {
            errors.put("driverId", ErrorCodes.FIELD_REQUIRED);
        } else if (driver == null) {
            errors.put("driverId", ErrorCodes.FIELD_INVALID_VALUE);
        }
        DriverViolation.Rule rule = InputFields.parseEnum(DriverViolation.Rule.class, "ruleCode", in.ruleCode(), true,
                errors);
        if (in.startedAt() == null) {
            errors.put("startedAt", ErrorCodes.FIELD_REQUIRED);
        } else if (in.endedAt() != null && in.endedAt().isBefore(in.startedAt())) {
            errors.put("endedAt", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (in.exceededMin() != null && (in.exceededMin() < 0 || in.exceededMin() > 100_000)) {
            errors.put("exceededMin", ErrorCodes.FIELD_INVALID_VALUE);
        }
        Vehicle vehicle = in.vehicleId() == null ? null : vehicles.findByIdAndDeletedFalse(in.vehicleId()).orElse(null);
        if (in.vehicleId() != null && vehicle == null) {
            errors.put("vehicleId", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        if (explanations.findByDriverIdAndRuleCodeAndStartedAt(driver.getId(), rule.name(), in.startedAt())
                .isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        DriverExplanation e = new DriverExplanation();
        e.setFirmaId(firma.getId());
        e.setDriverId(driver.getId());
        e.setRuleCode(rule.name());
        e.setVehiclePlate(vehicle == null ? null : vehicle.getPlate());
        e.setStartedAt(in.startedAt());
        e.setEndedAt(in.endedAt());
        e.setExceededMin(in.exceededMin());
        e.setStatus(DriverExplanation.Status.PENDING);
        e.setSource(DriverExplanation.Source.MANUAL);
        e.setDocLang(templates.documentLanguage(firma.getCountry()));
        explanations.save(e);
        notifyDriver(e, firma);
        return e;
    }

    /** A push to the driver's login (if it has one): the rule and the day, opening the explanation. */
    private void notifyDriver(DriverExplanation e, Firma firma) {
        Long userId = drivers.findById(e.getDriverId()).map(Driver::getUserId).orElse(null);
        if (userId == null) {
            return;
        }
        String day = DATE.format(e.getStartedAt().atZone(zone(firma)));
        String rule = e.getRuleCode();
        push.notify(List.of(userId), code -> new PushService.Payload(PUSH_TYPE,
                lang.get("notify.explanation.requested.title", code),
                lang.get("notify.explanation.requested.body", code,
                        rule == null ? "" : lang.get("hos.rule." + rule, code), day),
                "/driver/explanations/" + e.getId(), "explanation-" + e.getId()));
    }

    private byte[] render(DriverExplanation e, Firma firma, String docLang, byte[] signature, boolean draft) {
        ExplanationTemplates.Template t = templates.template(docLang);
        ZoneId zone = zone(firma);
        Driver driver = drivers.findById(e.getDriverId()).orElse(null);
        String card = cards.findByDriverIdOrderByIdDesc(e.getDriverId()).stream().findFirst()
                .map(DriverCard::getCardNumber).orElse(null);
        String values = e.getActualMin() == null || e.getLimitMin() == null ? null
                : t.duration(e.getActualMin()) + " / " + t.duration(e.getLimitMin());
        ExplanationPdf.Data data = new ExplanationPdf.Data(firma.getName(), oneLine(firma.getAddress()),
                driver == null ? null : driver.getFullName(), card, e.getVehiclePlate(),
                DATE.format(e.getStartedAt().atZone(zone)), TIME.format(e.getStartedAt().atZone(zone)),
                e.getEndedAt() == null ? null : TIME.format(e.getEndedAt().atZone(zone)),
                e.getRuleCode() == null ? null : lang.get("hos.rule." + e.getRuleCode(), docLang), values,
                e.getExceededMin() == null ? null : t.duration(e.getExceededMin()),
                e.getReason() == null ? null : e.getReason().name(), e.getReasonText(), e.getPlace(),
                e.getSignedAt() == null ? null : DATE.format(e.getSignedAt().atZone(zone)), signature);
        return pdf.render(data, t, draft);
    }

    private static byte[] signature(String dataUrl, Map<String, String> errors) {
        if (dataUrl == null || dataUrl.isBlank()) {
            errors.put("signature", ErrorCodes.FIELD_REQUIRED);
            return null;
        }
        if (!dataUrl.startsWith(PNG_PREFIX) || dataUrl.length() > MAX_SIGNATURE_BYTES * 4 / 3 + PNG_PREFIX.length() + 4) {
            errors.put("signature", ErrorCodes.FIELD_INVALID_VALUE);
            return null;
        }
        try {
            byte[] png = Base64.getDecoder().decode(dataUrl.substring(PNG_PREFIX.length()).strip());
            boolean isPng = png.length > 8 && (png[0] & 0xFF) == 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G';
            if (!isPng || png.length > MAX_SIGNATURE_BYTES) {
                errors.put("signature", ErrorCodes.FIELD_INVALID_VALUE);
                return null;
            }
            return png;
        } catch (IllegalArgumentException ex) {
            errors.put("signature", ErrorCodes.FIELD_INVALID_VALUE);
            return null;
        }
    }

    /** The explanation if the caller may see it: everyone with the privilege, a driver only their own. */
    private DriverExplanation visible(Long id) {
        DriverExplanation e = load(id);
        if (!self.full(Privileges.TACHO_HOS_VIEW)) {
            self.requireOwn(e.getDriverId());
        }
        return e;
    }

    private DriverExplanation load(Long id) {
        return explanations.findById(id).orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private static void requirePending(DriverExplanation e) {
        if (e.getStatus() != DriverExplanation.Status.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, ERROR_NOT_PENDING);
        }
    }

    private Firma firma() {
        return firmas.findById(InputFields.requireFirmaId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private static ZoneId zone(Firma firma) {
        try {
            return ZoneId.of(firma.getTimezone());
        } catch (DateTimeException | NullPointerException e) {
            return ZoneOffset.UTC;
        }
    }

    private List<ExplanationDto.Explanation> toDtos(List<DriverExplanation> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Driver> driverOf = drivers.findAllById(list.stream().map(DriverExplanation::getDriverId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Driver::getId, Function.identity()));
        Map<Long, AppUser> userOf = users.findAllById(list.stream()
                .flatMap(e -> java.util.stream.Stream.of(e.getCreatedBy(), e.getSignedBy()))
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
        Map<String, Vehicle> vehicleOf = vehicles.findByDeletedFalseOrderByPlateAsc().stream()
                .collect(Collectors.toMap(v -> plateKey(v.getPlate()), Function.identity(), (a, b) -> a));
        return list.stream().map(e -> {
            Driver d = driverOf.get(e.getDriverId());
            Vehicle v = e.getVehiclePlate() == null ? null : vehicleOf.get(plateKey(e.getVehiclePlate()));
            return new ExplanationDto.Explanation(e.getId(), e.getStatus().name(), e.getSource().name(),
                    new ExplanationDto.Ref(e.getDriverId(), d == null ? null : d.getFullName()),
                    e.getVehiclePlate() == null ? null
                            : new ExplanationDto.VehicleRef(v == null ? null : v.getId(), e.getVehiclePlate()),
                    e.getViolationId(), e.getRuleCode(), e.getStartedAt(), e.getEndedAt(), e.getActualMin(),
                    e.getLimitMin(), e.getExceededMin(), e.getReason() == null ? null : e.getReason().name(),
                    e.getReasonText(), e.getPlace(), e.getDocLang(), e.getSignedAt(), ref(userOf, e.getSignedBy()),
                    e.getCancelledAt(), e.getCreatedAt(), ref(userOf, e.getCreatedBy()), e.getPdfFileId() != null);
        }).toList();
    }

    private static ExplanationDto.Ref ref(Map<Long, AppUser> users, Long id) {
        AppUser u = id == null ? null : users.get(id);
        return u == null ? null : new ExplanationDto.Ref(u.getId(), u.getFullName().trim());
    }

    private static String plateKey(String plate) {
        return plate == null ? "" : plate.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private static String oneLine(String s) {
        return s == null ? null : s.replaceAll("\\s*\\r?\\n\\s*", ", ").strip();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

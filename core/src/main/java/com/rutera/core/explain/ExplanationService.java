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
        String reasonCode = in == null ? null : in.reason();
        DriverExplanation.Reason reason = ExplanationCatalog.reason(reasonCode).orElse(null);
        if (reasonCode == null || reasonCode.isBlank()) {
            errors.put("reason", ErrorCodes.FIELD_REQUIRED);
        } else if (reason == null) {
            errors.put("reason", ErrorCodes.FIELD_INVALID_VALUE);
        }
        String text = in == null ? null : InputFields.blankToNull(in.reasonText());
        if (reason != null && text == null && ExplanationCatalog.textRequired(reason)
                && !ExplanationCatalog.isAlias(reasonCode)) {
            errors.put("reasonText", ErrorCodes.FIELD_REQUIRED);
        }
        Art12Claim claim = art12(e, reason, in, errors);
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
        e.setArt12(claim.art12());
        e.setArt12Place(claim.place());
        e.setArt12BreakFrom(claim.breakFrom());
        e.setArt12BreakTo(claim.breakTo());
        e.setPrintoutNote(claim.printoutNote());
        e.setPrintoutNoteAt(claim.printoutNoteAt());
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

    /**
     * The reasons, each with whether it allows an Article 12 claim for the infringement of this rule (with no rule:
     * whether the reason ever does) and whether it needs the driver's text.
     */
    @RequiresPrivilege({Privileges.TACHO_HOS_VIEW, Privileges.DRIVER_APP_SELF_USE})
    public List<ExplanationDto.ReasonOption> reasons(String ruleCode) {
        ExplanationCatalog.Infringement inf;
        try {
            inf = ruleCode == null || ruleCode.isBlank() ? null : ExplanationCatalog.infringement(ruleCode.strip());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(Map.of("ruleCode", ErrorCodes.FIELD_INVALID_VALUE));
        }
        return java.util.Arrays.stream(DriverExplanation.Reason.values()).map(r -> new ExplanationDto.ReasonOption(
                r.name(), inf == null ? ExplanationCatalog.safetyReason(r) : ExplanationCatalog.safetyAllowed(inf, r),
                inf == null ? ExplanationCatalog.homeReason(r) : ExplanationCatalog.homeAllowed(inf, r),
                ExplanationCatalog.textRequired(r))).toList();
    }

    /**
     * The note to copy by hand onto the tachograph printout (Article 12), in the document's language, filled with
     * what is known: the reason, the Article 12 claim and its place, the break before a 2 h extension.
     */
    @RequiresPrivilege({Privileges.TACHO_HOS_VIEW, Privileges.DRIVER_APP_SELF_USE})
    @Transactional(readOnly = true)
    public ExplanationDto.PrintoutNote printoutNote(Long id, String reasonCode, String art12, String place,
                                                    String breakFrom, String breakTo) {
        DriverExplanation e = visible(id);
        Firma firma = firma();
        String docLang = templates.documentLanguage(firma.getCountry());
        ExplanationTemplates.Template t = templates.template(docLang);
        ZoneId zone = zone(firma);
        DriverExplanation.Reason reason = ExplanationCatalog.reason(reasonCode).orElse(e.getReason());
        DriverExplanation.Art12 claim = parseArt12(art12);
        if (claim == null) {
            claim = reason == DriverExplanation.Reason.REACH_HOME_OR_BASE ? DriverExplanation.Art12.HOME_1H
                    : DriverExplanation.Art12.SAFETY;
        }
        boolean home = claim == DriverExplanation.Art12.HOME_1H || claim == DriverExplanation.Art12.HOME_2H;
        Instant at = e.getEndedAt() != null ? e.getEndedAt() : e.getStartedAt();
        Driver driver = drivers.findById(e.getDriverId()).orElse(null);
        ExplanationCatalog.Infringement inf = e.getRuleCode() == null ? null
                : ExplanationCatalog.infringement(e.getRuleCode());
        Map<String, String> v = new java.util.HashMap<>();
        v.put("date", t.date(at.atZone(zone)));
        v.put("time", t.time(at.atZone(zone)));
        v.put("reasonShort", reason == null ? null : t.text("reason." + reason.name() + ".short"));
        v.put("infringementShort", inf == null ? null : t.text("printout.infringementShort." + inf.name()));
        v.put("excess", e.getExceededMin() == null ? null : t.duration(e.getExceededMin()));
        v.put("location", e.getPlace());
        String p = InputFields.blankToNull(place) != null ? place.strip() : e.getArt12Place();
        v.put("stopPlace", p);
        v.put("destination", p);
        v.put("breakFrom", InputFields.blankToNull(breakFrom) != null ? breakFrom.strip() : e.getArt12BreakFrom());
        v.put("breakTo", InputFields.blankToNull(breakTo) != null ? breakTo.strip() : e.getArt12BreakTo());
        v.put("driverName", driver == null ? null : driver.getFullName());
        v.put("cardNumber", cards.findByDriverIdOrderByIdDesc(e.getDriverId()).stream().findFirst()
                .map(DriverCard::getCardNumber).orElse(null));
        v.put("plate", e.getVehiclePlate());
        String text = t.fill(home ? "printout.art12Home" : "printout.art12Safety", v).replaceAll("\\s+", " ")
                .replace(" .", ".").strip();
        return new ExplanationDto.PrintoutNote(docLang, t.text("printout.instruction"), text);
    }

    /** The Article 12 claim of a signing, checked against the infringement and the reason. */
    private record Art12Claim(DriverExplanation.Art12 art12, String place, String breakFrom, String breakTo,
                              Boolean printoutNote, Instant printoutNoteAt) {
    }

    private static Art12Claim art12(DriverExplanation e, DriverExplanation.Reason reason,
                                    ExplanationDto.SignInput in, Map<String, String> errors) {
        DriverExplanation.Art12 art12 = in == null ? DriverExplanation.Art12.NONE : parseArt12(in.art12());
        if (art12 == null) {
            errors.put("art12", ErrorCodes.FIELD_INVALID_VALUE);
            return new Art12Claim(DriverExplanation.Art12.NONE, null, null, null, null, null);
        }
        if (art12 == DriverExplanation.Art12.NONE) {
            return new Art12Claim(art12, null, null, null, in == null ? null : in.printoutNote(), null);
        }
        ExplanationCatalog.Infringement inf = e.getRuleCode() == null ? null
                : ExplanationCatalog.infringement(e.getRuleCode());
        boolean home = art12 != DriverExplanation.Art12.SAFETY;
        boolean allowed = home ? ExplanationCatalog.homeAllowed(inf, reason)
                : ExplanationCatalog.safetyAllowed(inf, reason);
        if (!allowed) {
            errors.put("art12", ErrorCodes.FIELD_INVALID_VALUE);
        }
        Integer exceeded = e.getExceededMin();
        if (exceeded != null && ((art12 == DriverExplanation.Art12.HOME_1H && exceeded > 60)
                || (art12 == DriverExplanation.Art12.HOME_2H && exceeded > 120))) {
            errors.put("art12", ErrorCodes.FIELD_INVALID_VALUE);
        }
        String place = InputFields.blankToNull(in.art12Place());
        if (place == null) {
            errors.put("art12Place", ErrorCodes.FIELD_REQUIRED);
        } else if (place.length() > 100) {
            errors.put("art12Place", ErrorCodes.FIELD_TOO_LONG);
        }
        String from = InputFields.blankToNull(in.art12BreakFrom());
        String to = InputFields.blankToNull(in.art12BreakTo());
        if (art12 == DriverExplanation.Art12.HOME_2H) {
            if (from == null) {
                errors.put("art12BreakFrom", ErrorCodes.FIELD_REQUIRED);
            } else if (!from.matches("([01]\\d|2[0-3]):[0-5]\\d")) {
                errors.put("art12BreakFrom", ErrorCodes.FIELD_INVALID_FORMAT);
            }
            if (to == null) {
                errors.put("art12BreakTo", ErrorCodes.FIELD_REQUIRED);
            } else if (!to.matches("([01]\\d|2[0-3]):[0-5]\\d")) {
                errors.put("art12BreakTo", ErrorCodes.FIELD_INVALID_FORMAT);
            }
        } else {
            from = null;
            to = null;
        }
        if (in.printoutNote() == null) {
            errors.put("printoutNote", ErrorCodes.FIELD_REQUIRED);
        }
        return new Art12Claim(art12, place, from, to, in.printoutNote(),
                Boolean.TRUE.equals(in.printoutNote()) ? in.printoutNoteAt() : null);
    }

    /** NONE when empty, null when unknown. */
    private static DriverExplanation.Art12 parseArt12(String text) {
        if (text == null || text.isBlank()) {
            return DriverExplanation.Art12.NONE;
        }
        try {
            return DriverExplanation.Art12.valueOf(text.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
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
        int limit = ExplanationCatalog.limitMin(rule);
        e.setLimitMin(limit);
        if (in.exceededMin() != null) {
            int actual = ExplanationCatalog.minimum(rule) ? limit - in.exceededMin() : limit + in.exceededMin();
            e.setActualMin(actual < 0 ? null : actual);
        }
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
        DriverCard card = cards.findByDriverIdOrderByIdDesc(e.getDriverId()).stream().findFirst().orElse(null);
        ExplanationCatalog.Infringement inf = e.getRuleCode() == null ? null
                : ExplanationCatalog.infringement(e.getRuleCode());
        Map<String, String> v = new java.util.HashMap<>();
        v.put("firma.name", firma.getName());
        v.put("firma.address", oneLine(firma.getAddress()));
        v.put("firma.taxId", firma.getVatId());
        v.put("driver.fullName", driver == null ? null : driver.getFullName());
        v.put("driver.cardNumber", card == null ? null : card.getCardNumber());
        v.put("vehicle.plate", e.getVehiclePlate());
        v.put("doc.number", Long.toString(e.getId()));
        v.put("doc.place", e.getPlace());
        Instant docAt = e.getSignedAt() != null ? e.getSignedAt() : Instant.now();
        v.put("doc.date", t.date(docAt.atZone(zone)));
        v.put("source.label", t.text("source." + (e.getViolationId() != null || e.getSource()
                == DriverExplanation.Source.AUTO ? "BOTH" : "MANUAL")));
        v.put("source.downloadedAt", card == null || card.getLastDownloadAt() == null ? null
                : t.date(card.getLastDownloadAt().atZone(zone)));
        v.put("inf.date", t.date(e.getStartedAt().atZone(zone)));
        v.put("inf.period", t.date(e.getStartedAt().atZone(zone)) + " " + t.time(e.getStartedAt().atZone(zone))
                + (e.getEndedAt() == null ? "" : " – " + t.date(e.getEndedAt().atZone(zone)) + " "
                + t.time(e.getEndedAt().atZone(zone))));
        v.put("inf.limit", e.getLimitMin() == null ? null : t.duration(e.getLimitMin()));
        v.put("inf.recorded", e.getActualMin() == null ? null : t.duration(e.getActualMin()));
        v.put("inf.excess", e.getExceededMin() == null ? null : t.duration(e.getExceededMin()));
        v.put("reason.details", e.getReasonText());
        v.put("dispute.text", e.getReasonText());
        v.put("art12.stopPlace", e.getArt12Place());
        v.put("art12.destination", e.getArt12Place());
        v.put("art12.breakFrom", e.getArt12BreakFrom());
        v.put("art12.breakTo", e.getArt12BreakTo());
        if (e.getPrintoutNoteAt() != null) {
            v.put("art12.noteDate", t.date(e.getPrintoutNoteAt().atZone(zone)));
            v.put("art12.noteTime", t.time(e.getPrintoutNoteAt().atZone(zone)));
        }
        if (e.getSignedAt() != null) {
            v.put("sign.timestamp", t.date(e.getSignedAt().atZone(zone)) + " " + t.time(e.getSignedAt().atZone(zone)));
        }
        String label = e.getRuleCode() == null ? null : lang.get("hos.rule." + e.getRuleCode(), docLang);
        boolean possible = inf != null && (inf.safety || inf.home);
        ExplanationPdf.Doc doc = new ExplanationPdf.Doc(v, inf == null ? "OTHER" : inf.name(), label, e.getReason(),
                e.getArt12(), possible, e.getPrintoutNote(), signature, e.getSignedAt() != null);
        return pdf.render(doc, t, draft);
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
            ExplanationCatalog.Infringement inf = e.getRuleCode() == null ? null
                    : ExplanationCatalog.infringement(e.getRuleCode());
            return new ExplanationDto.Explanation(e.getId(), e.getStatus().name(), e.getSource().name(),
                    new ExplanationDto.Ref(e.getDriverId(), d == null ? null : d.getFullName()),
                    e.getVehiclePlate() == null ? null
                            : new ExplanationDto.VehicleRef(v == null ? null : v.getId(), e.getVehiclePlate()),
                    e.getViolationId(), e.getRuleCode(), inf == null ? null : inf.name(), e.getStartedAt(),
                    e.getEndedAt(), e.getActualMin(), e.getLimitMin(), e.getExceededMin(),
                    e.getReason() == null ? null : e.getReason().name(), e.getReasonText(), e.getPlace(),
                    e.getArt12() == null ? null : e.getArt12().name(), e.getArt12Place(), e.getArt12BreakFrom(),
                    e.getArt12BreakTo(), e.getPrintoutNote(), e.getPrintoutNoteAt(),
                    new ExplanationDto.Art12Allowed(inf != null && inf.safety, inf != null && inf.home),
                    e.getDocLang(), e.getSignedAt(), ref(userOf, e.getSignedBy()), e.getCancelledAt(),
                    e.getCreatedAt(), ref(userOf, e.getCreatedBy()), e.getPdfFileId() != null);
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

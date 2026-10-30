package com.rutera.core.driver;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Driver;
import com.rutera.common.domain.DriverCard;
import com.rutera.common.domain.DriverDocument;
import com.rutera.common.domain.Vehicle;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.DriverCardRepository;
import com.rutera.common.repository.DriverDocumentRepository;
import com.rutera.common.repository.DriverRepository;
import com.rutera.common.repository.VehicleRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Drivers of the firma with their tachograph card and documents ({@code /app/drivers}). The repositories are
 * tenant-filtered; every load by id also checks the firma of the row and answers 404 otherwise.
 * <p>
 * For now a driver has at most one card, edited with the driver: a new number overwrites the card (and resets
 * its download date), an empty number removes it. The usual vehicle of a driver is the vehicle whose
 * {@code default_driver_id} points at them (set in the vehicle form). Deleting a driver is a soft delete that
 * detaches them from vehicles, frees the card number and unlinks the login. Every change goes to the audit log.
 */
@Service
public class DriverService {

    public static final String MODULE = "driver";

    private static final Pattern CARD_SHAPE = Pattern.compile("[A-Z0-9]{1,32}");
    private static final Pattern EMAIL_SHAPE = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Set<String> NOT_AUDITED = Set.of("createdAt", "updatedAt", "nextDocument", "defaultVehicle",
            "name");

    private final DriverRepository drivers;
    private final DriverCardRepository cards;
    private final DriverDocumentRepository documents;
    private final VehicleRepository vehicles;
    private final AppUserRepository users;
    private final AuditService audit;

    public DriverService(DriverRepository drivers, DriverCardRepository cards, DriverDocumentRepository documents,
                         VehicleRepository vehicles, AppUserRepository users, AuditService audit) {
        this.drivers = drivers;
        this.cards = cards;
        this.documents = documents;
        this.vehicles = vehicles;
        this.users = users;
        this.audit = audit;
    }

    // --- drivers -----------------------------------------------------------------------------------------

    /** All non-deleted drivers of the firma, sorted by last and first name. */
    @RequiresPrivilege(Privileges.DRIVER_VIEW)
    @Transactional(readOnly = true)
    public List<DriverDto> list() {
        InputFields.requireFirmaId();
        List<Driver> all = drivers.findByDeletedFalseOrderByLastNameAscFirstNameAsc();
        if (all.isEmpty()) {
            return List.of();
        }
        List<Long> ids = all.stream().map(Driver::getId).toList();
        Map<Long, Vehicle> vehicleOf = new HashMap<>();
        for (Vehicle v : vehicles.findByDefaultDriverIdInAndDeletedFalseOrderByPlateAsc(ids)) {
            vehicleOf.putIfAbsent(v.getDefaultDriverId(), v);
        }
        Map<Long, DriverCard> cardOf = new HashMap<>();
        for (DriverCard card : cards.findByDriverIdIn(ids)) {
            cardOf.merge(card.getDriverId(), card, (a, b) -> a.getId() > b.getId() ? a : b);
        }
        Map<Long, DriverDocument> documentOf = new HashMap<>();
        for (DriverDocument d : documents.findByDriverIdInAndValidToNotNullOrderByValidToAscIdAsc(ids)) {
            documentOf.putIfAbsent(d.getDriverId(), d);
        }
        List<Long> userIds = all.stream().map(Driver::getUserId).filter(Objects::nonNull).toList();
        Map<Long, AppUser> userOf = userIds.isEmpty() ? Map.of()
                : users.findAllById(userIds).stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
        return all.stream()
                .map(d -> DriverDto.of(d, vehicleOf.get(d.getId()), cardOf.get(d.getId()),
                        d.getUserId() == null ? null : userOf.get(d.getUserId()),
                        nearest(documentOf.get(d.getId()), cardOf.get(d.getId()))))
                .toList();
    }

    @RequiresPrivilege(Privileges.DRIVER_VIEW)
    @Transactional(readOnly = true)
    public DriverDto get(Long id) {
        return toDto(load(id));
    }

    @RequiresPrivilege(Privileges.DRIVER_MANAGE)
    @Transactional
    public DriverDto create(DriverInput input) {
        Long firmaId = InputFields.requireFirmaId();
        Driver driver = new Driver();
        driver.setFirmaId(firmaId);
        CardInput card = apply(driver, input);
        driver = drivers.save(driver);
        saveCard(driver, null, card);
        audit.record(AuditEvent.of(MODULE, "driver_created", "Added driver " + driver.getFullName())
                .entity("driver", driver.getId())
                .detail("name", driver.getFullName()));
        return toDto(driver);
    }

    /** Full replacement of the editable fields, the card included. */
    @RequiresPrivilege(Privileges.DRIVER_MANAGE)
    @Transactional
    public DriverDto update(Long id, DriverInput input) {
        Driver driver = load(id);
        DriverDto before = toDto(driver);
        CardInput card = apply(driver, input);
        driver = drivers.save(driver);
        saveCard(driver, currentCard(driver), card);
        DriverDto after = toDto(driver);
        List<String> changed = changedFields(before, after);
        if (!changed.isEmpty()) {
            audit.record(AuditEvent.of(MODULE, "driver_updated", "Updated driver " + driver.getFullName())
                    .entity("driver", driver.getId())
                    .detail("fields", changed));
        }
        return after;
    }

    /** Soft delete: the row stays for history (trips, tachograph, payments). */
    @RequiresPrivilege(Privileges.DRIVER_MANAGE)
    @Transactional
    public void delete(Long id) {
        Driver driver = load(id);
        vehicles.clearDefaultDriver(driver.getId());
        cards.deleteAll(cards.findByDriverIdOrderByIdDesc(driver.getId()));
        driver.setDeleted(true);
        driver.setUserId(null);
        drivers.save(driver);
        audit.record(AuditEvent.of(MODULE, "driver_deleted", "Deleted driver " + driver.getFullName())
                .entity("driver", driver.getId())
                .detail("name", driver.getFullName()));
    }

    // --- documents ---------------------------------------------------------------------------------------

    /** Documents of a driver: nearest deadline first, documents without a deadline last. */
    @RequiresPrivilege(Privileges.DRIVER_VIEW)
    @Transactional(readOnly = true)
    public List<DriverDocumentDto> documents(Long driverId) {
        Driver driver = load(driverId);
        return documents.findByDriverId(driver.getId()).stream()
                .sorted(Comparator.comparing(DriverDocument::getValidTo,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(DriverDocument::getId))
                .map(DriverDocumentDto::of)
                .toList();
    }

    @RequiresPrivilege(Privileges.DRIVER_MANAGE)
    @Transactional
    public DriverDocumentDto createDocument(Long driverId, DriverDocumentInput input) {
        Driver driver = load(driverId);
        DriverDocument document = new DriverDocument();
        document.setFirmaId(driver.getFirmaId());
        document.setDriverId(driver.getId());
        apply(document, input);
        document = documents.save(document);
        audit.record(documentEvent("document_created", "Added document", driver, document));
        return DriverDocumentDto.of(document);
    }

    @RequiresPrivilege(Privileges.DRIVER_MANAGE)
    @Transactional
    public DriverDocumentDto updateDocument(Long driverId, Long documentId, DriverDocumentInput input) {
        Driver driver = load(driverId);
        DriverDocument document = loadDocument(driver, documentId);
        apply(document, input);
        document = documents.save(document);
        audit.record(documentEvent("document_updated", "Updated document", driver, document));
        return DriverDocumentDto.of(document);
    }

    @RequiresPrivilege(Privileges.DRIVER_MANAGE)
    @Transactional
    public void deleteDocument(Long driverId, Long documentId) {
        Driver driver = load(driverId);
        DriverDocument document = loadDocument(driver, documentId);
        documents.delete(document);
        audit.record(documentEvent("document_deleted", "Deleted document", driver, document));
    }

    // --- helpers -----------------------------------------------------------------------------------------

    private record CardInput(String number, String issuingCountry, LocalDate validTo) {
    }

    private Driver load(Long id) {
        Long firmaId = InputFields.requireFirmaId();
        return drivers.findByIdAndDeletedFalse(id)
                .filter(d -> firmaId.equals(d.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private DriverDocument loadDocument(Driver driver, Long documentId) {
        return documents.findByIdAndDriverId(documentId, driver.getId())
                .filter(d -> driver.getFirmaId().equals(d.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    /** The newest card of the driver (for now there is at most one). */
    private DriverCard currentCard(Driver driver) {
        return driver.getId() == null ? null
                : cards.findByDriverIdOrderByIdDesc(driver.getId()).stream().findFirst().orElse(null);
    }

    private DriverDto toDto(Driver driver) {
        Vehicle vehicle = vehicles.findByDefaultDriverIdInAndDeletedFalseOrderByPlateAsc(List.of(driver.getId()))
                .stream().findFirst().orElse(null);
        DriverCard card = currentCard(driver);
        AppUser user = driver.getUserId() == null ? null : users.findById(driver.getUserId()).orElse(null);
        DriverDocument document = documents.findByDriverIdInAndValidToNotNullOrderByValidToAscIdAsc(
                List.of(driver.getId())).stream().findFirst().orElse(null);
        return DriverDto.of(driver, vehicle, card, user, nearest(document, card));
    }

    /** The earlier of the nearest document deadline and the card's expiry (the card wins a tie). */
    private static DriverDto.Deadline nearest(DriverDocument document, DriverCard card) {
        LocalDate cardTo = card == null ? null : card.getValidTo();
        LocalDate documentTo = document == null ? null : document.getValidTo();
        if (cardTo != null && (documentTo == null || !cardTo.isAfter(documentTo))) {
            return new DriverDto.Deadline(DriverDto.CARD_DEADLINE, cardTo);
        }
        return documentTo == null ? null : new DriverDto.Deadline(document.getKind().name(), documentTo);
    }

    /** Validates the input and copies it onto the driver; the card is returned for {@link #saveCard}. */
    private CardInput apply(Driver driver, DriverInput in) {
        Map<String, String> errors = new LinkedHashMap<>();
        String email = InputFields.blankToNull(in.email());
        if (email != null && !EMAIL_SHAPE.matcher(email).matches()) {
            errors.put("email", ErrorCodes.FIELD_INVALID_FORMAT);
        }
        String country = InputFields.country("country", in.country(), errors);
        InputFields.dateOrder("employmentTo", in.employmentFrom(), in.employmentTo(), errors);
        Driver.Status status = InputFields.parseEnum(Driver.Status.class, "status", in.status(), false, errors);
        CardInput card = readCard(driver, in, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        driver.setFirstName(in.firstName().trim());
        driver.setLastName(in.lastName().trim());
        driver.setBirthDate(in.birthDate());
        driver.setPhone(InputFields.blankToNull(in.phone()));
        driver.setEmail(email);
        driver.setCountry(country);
        driver.setEmploymentFrom(in.employmentFrom());
        driver.setEmploymentTo(in.employmentTo());
        driver.setStatus(status == null ? Driver.Status.ACTIVE : status);
        driver.setNotes(InputFields.blankToNull(in.notes()));
        return card;
    }

    private CardInput readCard(Driver driver, DriverInput in, Map<String, String> errors) {
        String raw = in.cardNumber() == null ? "" : in.cardNumber().replaceAll("[\\s-]+", "").toUpperCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return null;
        }
        if (!CARD_SHAPE.matcher(raw).matches()) {
            errors.put("cardNumber", ErrorCodes.FIELD_INVALID_FORMAT);
            return null;
        }
        String country = InputFields.country("cardIssuingCountry", in.cardIssuingCountry(), errors);
        if (country == null && !errors.containsKey("cardIssuingCountry")) {
            errors.put("cardIssuingCountry", ErrorCodes.FIELD_REQUIRED);
        }
        cards.findByCardNumber(raw)
                .filter(other -> !other.getDriverId().equals(driver.getId()))
                .ifPresent(other -> errors.put("cardNumber", ErrorCodes.DRIVER_CARD_TAKEN));
        return new CardInput(raw, country, in.cardValidTo());
    }

    /** Creates, overwrites or removes the driver's card; a new number starts without a download date. */
    private void saveCard(Driver driver, DriverCard current, CardInput in) {
        if (in == null) {
            if (current != null) {
                cards.delete(current);
            }
            return;
        }
        DriverCard card = current == null ? new DriverCard() : current;
        if (current == null) {
            card.setFirmaId(driver.getFirmaId());
            card.setDriverId(driver.getId());
        } else if (!current.getCardNumber().equals(in.number())) {
            card.setLastDownloadAt(null);
            card.setGeneration(null);
            card.setValidFrom(null);
        }
        card.setCardNumber(in.number());
        card.setIssuingCountry(in.issuingCountry());
        card.setValidTo(in.validTo());
        try {
            cards.saveAndFlush(card);
        } catch (DataIntegrityViolationException e) {
            // Two requests at the same time: the unique key ux_driver_card_firma_number decides.
            throw ApiException.validation(Map.of("cardNumber", ErrorCodes.DRIVER_CARD_TAKEN));
        }
    }

    private void apply(DriverDocument document, DriverDocumentInput in) {
        Map<String, String> errors = new LinkedHashMap<>();
        DriverDocument.Kind kind = InputFields.parseEnum(DriverDocument.Kind.class, "kind", in.kind(), true, errors);
        InputFields.dateOrder("validTo", in.validFrom(), in.validTo(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        document.setKind(kind);
        document.setNumber(InputFields.blankToNull(in.number()));
        document.setValidFrom(in.validFrom());
        document.setValidTo(in.validTo());
        document.setRemindDays(in.remindDays() == null ? DriverDocument.DEFAULT_REMIND_DAYS : in.remindDays());
    }

    private static AuditEvent documentEvent(String action, String verb, Driver driver, DriverDocument document) {
        return AuditEvent.of(MODULE, action, verb + " " + document.getKind().name() + " of driver "
                        + driver.getFullName())
                .entity("driver_document", document.getId())
                .detail("driverId", driver.getId())
                .detail("kind", document.getKind().name())
                .detail("validTo", document.getValidTo() == null ? "" : document.getValidTo().toString());
    }

    /** Names of the record components that differ (for the audit entry of an update). */
    private static List<String> changedFields(DriverDto before, DriverDto after) {
        List<String> changed = new ArrayList<>();
        for (RecordComponent component : DriverDto.class.getRecordComponents()) {
            if (NOT_AUDITED.contains(component.getName())) {
                continue;
            }
            try {
                if (!Objects.equals(component.getAccessor().invoke(before), component.getAccessor().invoke(after))) {
                    changed.add(component.getName());
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return changed;
    }
}

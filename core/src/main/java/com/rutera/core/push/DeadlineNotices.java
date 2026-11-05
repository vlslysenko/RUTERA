package com.rutera.core.push;

import com.rutera.common.context.RequestContext;
import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Driver;
import com.rutera.common.domain.DriverCard;
import com.rutera.common.domain.DriverDocument;
import com.rutera.common.domain.Firma;
import com.rutera.common.domain.Vehicle;
import com.rutera.common.domain.VehicleDocument;
import com.rutera.common.i18n.LangService;
import com.rutera.common.i18n.SupportedLanguages;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.DriverCardRepository;
import com.rutera.common.repository.DriverDocumentRepository;
import com.rutera.common.repository.DriverRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.repository.VehicleDocumentRepository;
import com.rutera.common.repository.VehicleRepository;
import com.rutera.common.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Web Push reminders of expiring documents. Every hour, in the daytime of each firma's time zone (from
 * {@code rutera.push.deadline-hour} for 12 hours), a document or driver card that reached a lead time
 * ({@code rutera.push.deadline-days}) gets one notification per lead time: driver documents and cards to the login of
 * the driver, vehicle documents to the owners and dispatchers of the firma.
 * <p>
 * The {@code remindDays} of a document drops the longer lead times and is a lead time itself; 0 switches its reminders
 * off. Only the current item counts: the newest card of a driver, and for each kind (except OTHER) the document with
 * the latest date, so an old document replaced by a new record stays silent. Expired items get nothing.
 * {@code push_deadline_sent} keeps what went out, so a renewed date starts again. Nothing is recorded while no
 * recipient has a device: someone who subscribes later still gets the current reminder.
 */
@Service
public class DeadlineNotices {

    static final String DOCUMENT_URL = "/driver/documents";
    private static final int ACTIVE_HOURS = 12;
    /** Reaches any {@code remindDays} (at most 365). */
    private static final int MIN_LOOK_AHEAD_DAYS = 366;
    /** Records of past deadlines are kept this long after the date. */
    private static final int KEEP_DAYS = 60;
    private static final List<Integer> DEFAULT_LEAD_DAYS = List.of(1, 7, 14, 30);
    private static final Logger log = LoggerFactory.getLogger(DeadlineNotices.class);

    private final FirmaRepository firmas;
    private final DriverDocumentRepository driverDocuments;
    private final DriverCardRepository cards;
    private final VehicleDocumentRepository vehicleDocuments;
    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final AppUserRepository users;
    private final PushService push;
    private final LangService lang;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final List<Integer> leadDays;
    private final int startHour;

    public DeadlineNotices(FirmaRepository firmas, DriverDocumentRepository driverDocuments, DriverCardRepository cards,
                           VehicleDocumentRepository vehicleDocuments, DriverRepository drivers,
                           VehicleRepository vehicles, AppUserRepository users, PushService push, LangService lang,
                           JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                           @Value("${rutera.push.deadline-days:30,14,7,1}") String leadDays,
                           @Value("${rutera.push.deadline-hour:8}") int startHour) {
        this.firmas = firmas;
        this.driverDocuments = driverDocuments;
        this.cards = cards;
        this.vehicleDocuments = vehicleDocuments;
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.users = users;
        this.push = push;
        this.lang = lang;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.leadDays = parseDays(leadDays);
        this.startHour = startHour;
    }

    /** One item that may need a reminder; the texts take the language code of the recipient. */
    private record Due(String kind, long id, LocalDate validTo, Integer remindDays, List<Long> userIds, String type,
                       String tag, String url, Function<String, String> title, Function<String, String> label) {
    }

    /** One line of {@code push_deadline_sent}. */
    private record Sent(String kind, long id, LocalDate validTo, int threshold) {
    }

    /** Every hour at minute 20 (when {@code rutera.scheduling.enabled}): {@link #run(Instant)}. */
    @Scheduled(cron = "0 20 * * * *")
    public void hourly() {
        run(Instant.now());
    }

    /** Sends what is due at {@code now}; the number of reminders sent. */
    public int run(Instant now) {
        if (!push.enabled()) {
            return 0;
        }
        int sent = 0;
        try {
            List<Firma> active = RequestContext.runAsRoot(null, () -> tx.execute(s -> firmas.findAll().stream()
                    .filter(f -> f.isActive() && (f.getExpiresAt() == null || f.getExpiresAt().isAfter(now)))
                    .toList()));
            for (Firma firma : active) {
                ZonedDateTime local = now.atZone(zone(firma));
                if (local.getHour() >= startHour && local.getHour() < startHour + ACTIVE_HOURS) {
                    sent += runFirmaSafely(firma, local.toLocalDate(), now);
                }
            }
            jdbc.update("DELETE FROM push_deadline_sent WHERE valid_to < ?",
                    now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(KEEP_DAYS));
        } catch (Exception e) {
            log.warn("Deadline reminders failed: {}", e.toString());
        }
        return sent;
    }

    private int runFirmaSafely(Firma firma, LocalDate today, Instant now) {
        try {
            return runFirma(firma, today, now);
        } catch (Exception e) {
            log.warn("Deadline reminders of firma {} failed: {}", firma.getId(), e.toString());
            return 0;
        }
    }

    private int runFirma(Firma firma, LocalDate today, Instant now) throws Exception {
        LocalDate until = today.plusDays(Math.max(leadDays.getLast(), MIN_LOOK_AHEAD_DAYS));
        List<Due> items = RequestContext.runAsFirma(firma.getId(), null, () -> tx.execute(s -> {
            List<Due> all = new ArrayList<>();
            driverItems(today, until, all);
            vehicleItems(firma, today, until, all);
            return all;
        }));
        Set<Long> reachable = push.withDevices(items.stream().flatMap(d -> d.userIds().stream()).distinct().toList());
        if (reachable.isEmpty()) {
            return 0;
        }
        Set<Sent> done = new HashSet<>(jdbc.query("SELECT item_kind, item_id, valid_to, threshold_days "
                + "FROM push_deadline_sent WHERE firma_id = ?", (rs, i) -> new Sent(rs.getString(1), rs.getLong(2),
                rs.getObject(3, LocalDate.class), rs.getInt(4)), firma.getId()));
        int sent = 0;
        for (Due d : items) {
            Integer threshold = threshold(leadDays, ChronoUnit.DAYS.between(today, d.validTo()), d.remindDays());
            if (threshold == null || done.contains(new Sent(d.kind(), d.id(), d.validTo(), threshold))) {
                continue;
            }
            List<Long> recipients = d.userIds().stream().filter(reachable::contains).toList();
            if (!recipients.isEmpty() && claim(firma.getId(), d, threshold, now)) {
                push.notify(recipients, code -> payload(d, code));
                sent++;
            }
        }
        return sent;
    }

    private PushService.Payload payload(Due d, String code) {
        Locale locale = SupportedLanguages.toLocale(code);
        String date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(d.validTo());
        return new PushService.Payload(d.type(), d.title().apply(code),
                lang.get("notify.deadline.body", code, d.label().apply(code), date), d.url(), d.tag());
    }

    private void driverItems(LocalDate today, LocalDate until, List<Due> all) {
        List<DriverDocument> documents = driverDocuments.findByValidToBetween(today, until);
        List<DriverCard> driverCards = cards.findByValidToBetween(today, until);
        Set<Long> ids = documents.stream().map(DriverDocument::getDriverId).collect(Collectors.toSet());
        driverCards.forEach(c -> ids.add(c.getDriverId()));
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, Long> loginOf = activeLogins(ids);
        // Sorted by date: the last one seen per driver and kind has the latest.
        Map<String, LocalDate> latest = new HashMap<>();
        for (DriverDocument d : driverDocuments.findByDriverIdInAndValidToNotNullOrderByValidToAscIdAsc(ids)) {
            latest.put(d.getDriverId() + ":" + d.getKind(), d.getValidTo());
        }
        Map<Long, Long> currentCard = new HashMap<>();
        for (DriverCard card : cards.findByDriverIdIn(ids)) {
            currentCard.merge(card.getDriverId(), card.getId(), Math::max);
        }
        for (DriverDocument doc : documents) {
            Long login = loginOf.get(doc.getDriverId());
            boolean replaced = doc.getKind() != DriverDocument.Kind.OTHER
                    && doc.getValidTo().isBefore(latest.get(doc.getDriverId() + ":" + doc.getKind()));
            if (login != null && !replaced) {
                all.add(new Due("DRIVER_DOCUMENT", doc.getId(), doc.getValidTo(), doc.getRemindDays(), List.of(login),
                        "deadline.document", "deadline-document-" + doc.getId(), DOCUMENT_URL,
                        code -> lang.get("notify.deadline.document.title", code),
                        code -> lang.get("driver.document." + doc.getKind().name(), code)));
            }
        }
        for (DriverCard card : driverCards) {
            Long login = loginOf.get(card.getDriverId());
            if (login != null && card.getId().equals(currentCard.get(card.getDriverId()))) {
                all.add(new Due("DRIVER_CARD", card.getId(), card.getValidTo(), null, List.of(login),
                        "deadline.card", "deadline-card-" + card.getId(), DOCUMENT_URL,
                        code -> lang.get("notify.deadline.card.title", code),
                        code -> lang.get("driver.document.DRIVER_CARD", code)));
            }
        }
    }

    /** Driver id to login, for working drivers with an active login. */
    private Map<Long, Long> activeLogins(Set<Long> driverIds) {
        List<Driver> working = drivers.findAllById(driverIds).stream()
                .filter(d -> d.getUserId() != null && !d.isDeleted() && d.getStatus() == Driver.Status.ACTIVE)
                .toList();
        Set<Long> active = users.findAllById(working.stream().map(Driver::getUserId).toList()).stream()
                .filter(u -> u.isActive() && !u.isDeleted())
                .map(AppUser::getId)
                .collect(Collectors.toSet());
        Map<Long, Long> loginOf = new HashMap<>();
        for (Driver d : working) {
            if (active.contains(d.getUserId())) {
                loginOf.put(d.getId(), d.getUserId());
            }
        }
        return loginOf;
    }

    private void vehicleItems(Firma firma, LocalDate today, LocalDate until, List<Due> all) {
        List<VehicleDocument> documents = vehicleDocuments.findByValidToBetween(today, until);
        if (documents.isEmpty()) {
            return;
        }
        Set<Long> ids = documents.stream().map(VehicleDocument::getVehicleId).collect(Collectors.toSet());
        Map<Long, Vehicle> vehicleOf = new HashMap<>();
        for (Vehicle v : vehicles.findAllById(ids)) {
            if (!v.isDeleted() && v.getStatus() != Vehicle.Status.INACTIVE) {
                vehicleOf.put(v.getId(), v);
            }
        }
        // Sorted by date: the last one seen per vehicle and kind has the latest.
        Map<String, LocalDate> latest = new HashMap<>();
        for (VehicleDocument d : vehicleDocuments.findByVehicleIdInAndValidToNotNullOrderByValidToAscIdAsc(ids)) {
            latest.put(d.getVehicleId() + ":" + d.getKind(), d.getValidTo());
        }
        List<Long> office = users.findByFirmaIdAndDeletedFalseOrderByLastNameAscFirstNameAsc(firma.getId()).stream()
                .filter(AppUser::isActive)
                .filter(u -> u.getRole() == Role.OWNER || u.getRole() == Role.DISPATCHER)
                .map(AppUser::getId)
                .toList();
        for (VehicleDocument doc : documents) {
            Vehicle v = vehicleOf.get(doc.getVehicleId());
            boolean replaced = doc.getKind() != VehicleDocument.Kind.OTHER
                    && doc.getValidTo().isBefore(latest.get(doc.getVehicleId() + ":" + doc.getKind()));
            if (v != null && !replaced) {
                all.add(new Due("VEHICLE_DOCUMENT", doc.getId(), doc.getValidTo(), doc.getRemindDays(), office,
                        "deadline.vehicle_document", "deadline-vehicle-" + doc.getId(), "/fleet/" + v.getId(),
                        code -> lang.get("notify.deadline.vehicle_document.title", code, v.getPlate()),
                        code -> lang.get("vehicle.document." + doc.getKind().name(), code)));
            }
        }
    }

    /** Records the reminder; false when another run has just sent it. */
    private boolean claim(Long firmaId, Due d, int threshold, Instant now) {
        try {
            jdbc.update("INSERT INTO push_deadline_sent (firma_id, item_kind, item_id, valid_to, threshold_days, "
                    + "sent_at) VALUES (?, ?, ?, ?, ?, ?)", firmaId, d.kind(), d.id(), d.validTo(), threshold,
                    Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * The lead time an item is in, {@code daysLeft} before its date: the smallest one not below it. Null when the item
     * is expired, not near yet or has its reminders off. {@code remindDays} (null: no limit) drops the longer lead
     * times and is one itself; 0 switches reminders off.
     */
    static Integer threshold(List<Integer> leadDays, long daysLeft, Integer remindDays) {
        if (daysLeft < 0 || (remindDays != null && remindDays <= 0)) {
            return null;
        }
        TreeSet<Integer> allowed = new TreeSet<>(leadDays);
        if (remindDays != null) {
            allowed.removeIf(t -> t > remindDays);
            allowed.add(remindDays);
        }
        return allowed.ceiling((int) Math.min(daysLeft, Integer.MAX_VALUE));
    }

    /** Positive lead times, ascending; the default when the setting is unusable. */
    static List<Integer> parseDays(String text) {
        TreeSet<Integer> days = new TreeSet<>();
        try {
            for (String part : text.split(",")) {
                int d = Integer.parseInt(part.strip());
                if (d > 0) {
                    days.add(d);
                }
            }
        } catch (NumberFormatException | NullPointerException e) {
            days.clear();
        }
        if (days.isEmpty()) {
            log.warn("rutera.push.deadline-days cannot be used ({}): using {}", text, DEFAULT_LEAD_DAYS);
            return DEFAULT_LEAD_DAYS;
        }
        return List.copyOf(days);
    }

    private static ZoneId zone(Firma firma) {
        try {
            return ZoneId.of(firma.getTimezone());
        } catch (DateTimeException | NullPointerException e) {
            return ZoneOffset.UTC;
        }
    }
}

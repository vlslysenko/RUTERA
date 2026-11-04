package com.rutera.core.push;

import com.rutera.common.domain.Driver;
import com.rutera.common.domain.DriverAbsence;
import com.rutera.common.domain.Trip;
import com.rutera.common.i18n.LangService;
import com.rutera.common.i18n.SupportedLanguages;
import com.rutera.common.repository.DriverRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.core.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The notifications of the driver cabinet, sent to the login linked to a driver (never to the user who made the
 * change): a trip assigned, changed, cancelled or no longer theirs, and the office's decision on an absence request.
 * Texts come from the i18n bundles ({@code notify.*}) in the user's language; times in the firma's time zone.
 */
@Component
public class PushNotices {

    static final String DRIVER_URL = "/driver";
    static final String ABSENCE_URL = "/driver/me";

    private final PushService push;
    private final DriverRepository drivers;
    private final FirmaRepository firmas;
    private final LangService lang;

    public PushNotices(PushService push, DriverRepository drivers, FirmaRepository firmas, LangService lang) {
        this.push = push;
        this.drivers = drivers;
        this.firmas = firmas;
        this.lang = lang;
    }

    /** A new trip with these drivers on the crew. */
    public void tripCreated(Trip trip, Collection<Long> crew) {
        tripNotice(trip, crew, "trip.assigned");
    }

    /** A saved trip: drivers added get "assigned", removed ones "unassigned", the others "changed" or "cancelled". */
    public void tripUpdated(Trip trip, Set<Long> before, Set<Long> after, Trip.Status statusBefore) {
        if (!push.enabled()) {
            return;
        }
        boolean cancelled = trip.getStatus() == Trip.Status.CANCELLED && statusBefore != Trip.Status.CANCELLED;
        Set<Long> added = new HashSet<>(after);
        added.removeAll(before);
        Set<Long> removed = new HashSet<>(before);
        removed.removeAll(after);
        Set<Long> staying = new HashSet<>(after);
        staying.retainAll(before);
        if (cancelled) {
            Set<Long> all = new HashSet<>(before);
            all.addAll(after);
            tripNotice(trip, all, "trip.cancelled");
            return;
        }
        tripNotice(trip, added, "trip.assigned");
        tripNotice(trip, removed, "trip.unassigned");
        tripNotice(trip, staying, "trip.changed");
    }

    /** The office approved or rejected an absence of this driver. */
    public void absenceDecided(Driver driver, DriverAbsence a) {
        if (!push.enabled() || driver.getUserId() == null || isMe(driver.getUserId())
                || a.getStatus() == DriverAbsence.Status.REQUESTED) {
            return;
        }
        String type = a.getStatus() == DriverAbsence.Status.APPROVED ? "absence.approved" : "absence.rejected";
        push.notify(Set.of(driver.getUserId()), code -> {
            Locale locale = SupportedLanguages.toLocale(code);
            String body = lang.get("notify.absence.body", code, lang.get("absence.kind." + a.getKind().name(), code),
                    date(a.getFromDate(), locale), date(a.getToDate(), locale));
            if (a.getDecisionNote() != null) {
                body = body + " " + a.getDecisionNote();
            }
            return new PushService.Payload(type, lang.get("notify." + type + ".title", code), body, ABSENCE_URL,
                    "absence-" + a.getId());
        });
    }

    private void tripNotice(Trip trip, Collection<Long> driverIds, String type) {
        if (!push.enabled() || driverIds.isEmpty()) {
            return;
        }
        Set<Long> userIds = new HashSet<>();
        for (Driver d : drivers.findAllById(driverIds)) {
            if (d.getUserId() != null && !isMe(d.getUserId())) {
                userIds.add(d.getUserId());
            }
        }
        if (userIds.isEmpty()) {
            return;
        }
        ZoneId zone = zone(trip.getFirmaId());
        Instant start = trip.getPlannedStart();
        push.notify(userIds, code -> {
            Locale locale = SupportedLanguages.toLocale(code);
            String title = lang.get("notify." + type + ".title", code, trip.getNumber());
            String body = "trip.assigned".equals(type)
                    ? lang.get("notify.trip.assigned.body", code, start == null ? lang.get("notify.start.none", code)
                    : DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
                    .withZone(zone).format(start))
                    : lang.get("notify." + type + ".body", code);
            return new PushService.Payload(type, title, body, DRIVER_URL, "trip-" + trip.getId());
        });
    }

    private static boolean isMe(Long userId) {
        return CurrentUser.get().map(u -> Objects.equals(u.id(), userId)).orElse(false);
    }

    private ZoneId zone(Long firmaId) {
        try {
            return firmas.findById(firmaId).map(f -> ZoneId.of(f.getTimezone())).orElse(ZoneOffset.UTC);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static String date(LocalDate day, Locale locale) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day);
    }
}

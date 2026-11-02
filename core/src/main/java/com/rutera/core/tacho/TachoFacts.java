package com.rutera.core.tacho;

import com.rutera.common.domain.TachoFileRecord;
import com.rutera.tacho.TachoFile;
import com.rutera.tacho.card.CardActivityDay;
import com.rutera.tacho.card.CardVehicleUse;
import com.rutera.tacho.card.DriverCardFile;
import com.rutera.tacho.model.Activity;
import com.rutera.tacho.model.ActivityPeriod;
import com.rutera.tacho.model.Slot;
import com.rutera.tacho.vu.VuActivityDay;
import com.rutera.tacho.vu.VuCardInsertion;
import com.rutera.tacho.vu.VuFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Step B of the import: the activity intervals of a parsed file go to {@code tacho_activity} as they are (the
 * driver's timeline is merged when it is read, see {@link HosService}). Plain JDBC in batches: a card year is some
 * ten thousand rows. Every statement names the firma explicitly (no Hibernate tenant filter here).
 * <ul>
 *   <li>Driver card: every interval; with the card withdrawn the activity is UNKNOWN unless it was entered by hand.
 *       The vehicle is the one the card was used in at the start of the interval (EF Vehicles_Used).</li>
 *   <li>Vehicle unit: intervals of a slot with a driver card inserted, with that card's number; the vehicle is the
 *       unit's own. Intervals without a card belong to nobody and are not stored.</li>
 * </ul>
 */
@Component
public class TachoFacts {

    /** Bumped when the extraction changes: older files are extracted again at the next start. */
    public static final int VERSION = 1;

    public static final String CARD = "CARD";
    public static final String VU = "VU";

    private static final String INSERT = "insert into tacho_activity (firma_id, file_id, source, card_number, slot,"
            + " crew, activity, start_at, end_at, vehicle_plate, vehicle_vin) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    public TachoFacts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Replaces the facts of the file and marks it extracted. Returns the card numbers found (driver cards). */
    public Set<String> extract(TachoFileRecord f, TachoFile parsed) {
        delete(f.getFirmaId(), f.getId());
        List<Object[]> rows = new ArrayList<>();
        Set<String> cards = new LinkedHashSet<>();
        if (parsed instanceof DriverCardFile card) {
            cardRows(f, card, rows);
            if (f.getCardNumber() != null) {
                cards.add(f.getCardNumber());
            }
        } else if (parsed instanceof VuFile vu) {
            vuRows(f, vu, rows, cards);
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(INSERT, rows);
        }
        f.setFactsVersion(VERSION);
        return cards;
    }

    public void delete(Long firmaId, Long fileId) {
        jdbc.update("delete from tacho_activity where firma_id = ? and file_id = ?", firmaId, fileId);
    }

    /** The driver card numbers that appear in a file's facts. */
    public Set<String> cardNumbers(Long firmaId, Long fileId) {
        return new LinkedHashSet<>(jdbc.queryForList("select distinct card_number from tacho_activity"
                + " where firma_id = ? and file_id = ? and card_number is not null", String.class, firmaId, fileId));
    }

    private static void cardRows(TachoFileRecord f, DriverCardFile card, List<Object[]> rows) {
        List<CardVehicleUse> uses = card.vehiclesUsed();
        for (CardActivityDay day : card.activityDays()) {
            for (ActivityPeriod p : day.periods()) {
                String activity;
                if (p.cardPresent()) {
                    activity = activity(p.activity());
                } else {
                    // Card withdrawn: the activity is only meaningful when it was entered by hand ('c' bit).
                    activity = p.crew() ? activity(p.activity()) : "UNKNOWN";
                }
                CardVehicleUse use = p.cardPresent() ? vehicleAt(uses, p.start()) : null;
                rows.add(row(f, CARD, f.getCardNumber(), p, p.cardPresent() && p.crew(), activity,
                        use == null || use.registration() == null ? null : plate(use.registration().number()),
                        use == null ? null : vin(use.vin())));
            }
        }
    }

    private static void vuRows(TachoFileRecord f, VuFile vu, List<Object[]> rows, Set<String> cards) {
        List<VuCardInsertion> insertions = new ArrayList<>();
        for (VuActivityDay day : vu.activityDays()) {
            insertions.addAll(day.cardInsertions());
        }
        for (VuActivityDay day : vu.activityDays()) {
            for (ActivityPeriod p : day.periods()) {
                if (!p.cardPresent()) {
                    continue;
                }
                String number = cardIn(insertions, p.slot(), p.start());
                if (number == null) {
                    continue;
                }
                cards.add(number);
                rows.add(row(f, VU, number, p, p.crew(), activity(p.activity()), f.getPlate(), f.getVin()));
            }
        }
    }

    private static Object[] row(TachoFileRecord f, String source, String card, ActivityPeriod p, boolean crew,
                                String activity, String plate, String vin) {
        return new Object[] {f.getFirmaId(), f.getId(), source, card, p.slot() == Slot.DRIVER ? 1 : 2, crew, activity,
                Timestamp.from(p.start()), Timestamp.from(p.end()), plate, vin};
    }

    /** The driver card in the slot at that moment, by the unit's card insertion records. */
    private static String cardIn(List<VuCardInsertion> insertions, Slot slot, Instant at) {
        for (VuCardInsertion i : insertions) {
            if (i.slot() != slot || i.card() == null || !i.card().isDriverCard() || i.insertionTime() == null
                    || i.insertionTime().isAfter(at)) {
                continue;
            }
            if (i.withdrawalTime() == null || at.isBefore(i.withdrawalTime())) {
                return i.card().number();
            }
        }
        return null;
    }

    private static CardVehicleUse vehicleAt(List<CardVehicleUse> uses, Instant at) {
        CardVehicleUse found = null;
        for (CardVehicleUse u : uses) {
            if (u.firstUse() != null && !u.firstUse().isAfter(at) && (u.lastUse() == null || !u.lastUse().isBefore(at))) {
                found = u;
            }
        }
        return found;
    }

    private static String activity(Activity a) {
        return a == Activity.BREAK_REST ? "REST" : a.name();
    }

    private static String plate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String plate = raw.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return plate.length() > 20 ? plate.substring(0, 20) : plate;
    }

    private static String vin(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String vin = raw.strip().toUpperCase(Locale.ROOT);
        return vin.length() > 17 ? vin.substring(0, 17) : vin;
    }
}

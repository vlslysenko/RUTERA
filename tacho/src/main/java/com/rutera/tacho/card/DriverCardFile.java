package com.rutera.tacho.card;

import com.rutera.tacho.FileKind;
import com.rutera.tacho.Generation;
import com.rutera.tacho.TachoFile;
import com.rutera.tacho.TachoFileSummary;
import com.rutera.tacho.model.ActivityChange;
import com.rutera.tacho.model.PlaceRecord;
import com.rutera.tacho.model.SpecificCondition;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * A driver card download. A second generation card carries two applications: {@link #tachograph()} (GEN1 part,
 * written by every VU) and {@link #tachographG2()} (written only by second generation VUs, with GNSS positions
 * and VINs). The merged accessors combine both and prefer the second generation record for the same moment.
 *
 * @param tachograph DF Tachograph (GEN1 application); null only if the download tool left it out
 * @param tachographG2 DF Tachograph_G2; null on a first generation card
 */
public record DriverCardFile(Generation generation, CardIcc icc, CardApplication tachograph,
                             CardApplication tachographG2, List<String> warnings) implements TachoFile {

    private static final Comparator<CardEventFault> BY_BEGIN =
            Comparator.comparing(CardEventFault::begin, Comparator.nullsFirst(Comparator.naturalOrder()));

    @Override
    public FileKind kind() {
        return FileKind.DRIVER_CARD;
    }

    /** The second generation application if present, otherwise the first generation one. */
    public CardApplication primary() {
        return tachographG2 != null && tachographG2.identification() != null ? tachographG2 : tachograph;
    }

    public CardIdentification identification() {
        return firstNonNull(CardApplication::identification);
    }

    public DriverCardHolder holder() {
        return firstNonNull(CardApplication::holder);
    }

    public DrivingLicence drivingLicence() {
        return firstNonNull(CardApplication::drivingLicence);
    }

    /** EF Card_Download: the later value of both applications. */
    public Instant lastCardDownload() {
        return applications().stream().map(CardApplication::lastCardDownload).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    /** Activity days of both applications by date; a second generation day replaces the same GEN1 day. */
    public List<CardActivityDay> activityDays() {
        TreeMap<LocalDate, CardActivityDay> days = new TreeMap<>();
        for (CardApplication app : applications()) {
            for (CardActivityDay day : app.activityDays()) {
                if (!day.changes().isEmpty() || !days.containsKey(day.date())) {
                    days.put(day.date(), day);
                }
            }
        }
        return new ArrayList<>(days.values());
    }

    public List<CardVehicleUse> vehiclesUsed() {
        return merge(CardApplication::vehiclesUsed,
                v -> v.firstUse() + "|" + (v.registration() == null ? "" : v.registration().number()),
                Comparator.comparing(CardVehicleUse::firstUse, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    public List<PlaceRecord> places() {
        return merge(CardApplication::places, p -> p.entryTime() + "|" + p.entryType(),
                Comparator.comparing(PlaceRecord::entryTime, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    public List<CardEventFault> events() {
        return merge(CardApplication::events, e -> e.type() + "|" + e.begin(), BY_BEGIN);
    }

    public List<CardEventFault> faults() {
        return merge(CardApplication::faults, e -> e.type() + "|" + e.begin(), BY_BEGIN);
    }

    public List<SpecificCondition> specificConditions() {
        return merge(CardApplication::specificConditions, c -> c.time() + "|" + c.type(),
                Comparator.comparing(SpecificCondition::time, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    @Override
    public TachoFileSummary summary() {
        CardIdentification id = identification();
        List<CardActivityDay> days = activityDays();
        LocalDate from = days.isEmpty() ? null : days.getFirst().date();
        LocalDate to = days.isEmpty() ? null : days.getLast().date();
        return new TachoFileSummary(FileKind.DRIVER_CARD, generation,
                id == null ? null : id.cardNumber(),
                id == null ? null : id.issuingNation().display(),
                id == null || id.expiryDate() == null ? null : LocalDate.ofInstant(id.expiryDate(), ZoneOffset.UTC),
                null, null, null, from, to, latestDataAt(days));
    }

    /** The later of EF Card_Download, the last activity change, the last vehicle use and the current session. */
    private Instant latestDataAt(List<CardActivityDay> days) {
        List<Instant> candidates = new ArrayList<>();
        candidates.add(lastCardDownload());
        if (!days.isEmpty()) {
            CardActivityDay last = days.getLast();
            int minute = last.changes().stream().mapToInt(ActivityChange::minuteOfDay).filter(m -> m < 1440)
                    .max().orElse(0);
            candidates.add(last.date().atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(minute * 60L));
        }
        for (CardVehicleUse v : vehiclesUsed()) {
            candidates.add(v.lastUse());
        }
        for (CardApplication app : applications()) {
            if (app.currentUse() != null) {
                candidates.add(app.currentUse().sessionOpenTime());
            }
        }
        Instant now = Instant.now().plusSeconds(86_400);
        return candidates.stream().filter(Objects::nonNull).filter(t -> t.isBefore(now))
                .max(Comparator.naturalOrder()).orElse(null);
    }

    private List<CardApplication> applications() {
        List<CardApplication> apps = new ArrayList<>(2);
        if (tachograph != null) {
            apps.add(tachograph);
        }
        if (tachographG2 != null) {
            apps.add(tachographG2);
        }
        return apps;
    }

    private <T> T firstNonNull(Function<CardApplication, T> getter) {
        if (tachographG2 != null && getter.apply(tachographG2) != null) {
            return getter.apply(tachographG2);
        }
        return tachograph == null ? null : getter.apply(tachograph);
    }

    /** Union of both applications by key; the second generation record wins (it is added last). */
    private <T> List<T> merge(Function<CardApplication, List<T>> getter, Function<T, String> key,
                              Comparator<T> order) {
        Map<String, T> byKey = new LinkedHashMap<>();
        for (CardApplication app : applications()) {
            for (T item : getter.apply(app)) {
                byKey.put(key.apply(item), item);
            }
        }
        List<T> result = new ArrayList<>(byKey.values());
        result.sort(order);
        return result;
    }
}

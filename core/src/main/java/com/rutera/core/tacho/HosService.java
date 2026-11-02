package com.rutera.core.tacho;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Driver;
import com.rutera.common.domain.DriverCard;
import com.rutera.common.domain.DriverViolation;
import com.rutera.common.domain.Vehicle;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.DriverCardRepository;
import com.rutera.common.repository.DriverRepository;
import com.rutera.common.repository.DriverViolationRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.repository.VehicleRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.driver.DriverSelf;
import com.rutera.core.fleet.VehicleService;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.tacho.hos.HosEngine;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Step D of the import and the driver activity screens: merges a driver's timeline from {@code tacho_activity},
 * runs the 561/2006 rules ({@link HosEngine}) and keeps {@code driver_violation} in line.
 * <p>
 * Timeline of a driver: the intervals of the driver card files assigned to them; for a UTC day the newest card file
 * with data wins. Days without card data are filled from vehicle unit files in which one of the driver's cards was
 * inserted (matched by the driver identification, the first 14 characters of the card number, so renewed cards
 * count), the newest file winning for the same moment. Days are cut in the firma's time zone.
 */
@Service
public class HosService {

    public static final String MODULE = "tacho";
    public static final int MAX_DAYS = 31;
    /** Days before the requested range that the rules look at (weeks, reduced rests since the last weekly rest). */
    private static final int LOOKBACK_DAYS = 35;

    private final NamedParameterJdbcTemplate jdbc;
    private final DriverRepository drivers;
    private final DriverCardRepository cards;
    private final DriverViolationRepository violations;
    private final VehicleRepository vehicles;
    private final FirmaRepository firmas;
    private final AppUserRepository users;
    private final DriverSelf self;
    private final AuditService audit;

    public HosService(NamedParameterJdbcTemplate jdbc, DriverRepository drivers, DriverCardRepository cards,
                      DriverViolationRepository violations, VehicleRepository vehicles, FirmaRepository firmas,
                      AppUserRepository users, DriverSelf self, AuditService audit) {
        this.self = self;
        this.jdbc = jdbc;
        this.drivers = drivers;
        this.cards = cards;
        this.violations = violations;
        this.vehicles = vehicles;
        this.firmas = firmas;
        this.users = users;
        this.audit = audit;
    }

    /** One stored interval with what the screens need besides the rules. */
    record Row(Instant start, Instant end, String activity, int slot, String plate, String vin, long fileId,
               Instant downloadedAt, boolean card) {

        HosEngine.Seg seg() {
            return new HosEngine.Seg(start, end, HosEngine.Act.valueOf(activity), slot, plate);
        }
    }

    // --- activity --------------------------------------------------------------------------------------------

    @RequiresPrivilege({Privileges.TACHO_HOS_VIEW, Privileges.DRIVER_APP_SELF_USE})
    @Transactional(readOnly = true)
    public HosDto.Activity activity(Long driverId, LocalDate from, LocalDate to) {
        Long firmaId = InputFields.requireFirmaId();
        self.check(Privileges.TACHO_HOS_VIEW, driverId);
        Map<String, String> errors = new LinkedHashMap<>();
        if (driverId == null) {
            errors.put("driverId", ErrorCodes.FIELD_REQUIRED);
        }
        if (from == null) {
            errors.put("from", ErrorCodes.FIELD_REQUIRED);
        }
        if (to == null) {
            errors.put("to", ErrorCodes.FIELD_REQUIRED);
        } else if (from != null && (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS)) {
            errors.put("to", ErrorCodes.FIELD_INVALID_VALUE);
        }
        Driver driver = driverId == null ? null : drivers.findByIdAndDeletedFalse(driverId)
                .filter(d -> firmaId.equals(d.getFirmaId())).orElse(null);
        if (driverId != null && driver == null) {
            errors.put("driverId", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        ZoneId zone = zone(firmaId);
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        List<Row> rows = timeline(firmaId, driver, start.minus(Duration.ofDays(LOOKBACK_DAYS)), end);
        HosEngine engine = HosEngine.run(rows.stream().map(Row::seg).toList(), zone);
        Map<LocalDate, Integer> perDay = new HashMap<>();
        for (DriverViolation v : violations.findByDriverIdAndStartAtBetween(driver.getId(), start, end)) {
            if (v.getStartAt().isBefore(end)) {
                perDay.merge(v.getStartAt().atZone(zone).toLocalDate(), 1, Integer::sum);
            }
        }
        List<HosDto.Day> days = engine.days(from, to).stream().map(d -> new HosDto.Day(d.day(), d.drivingMin(),
                d.workMin(), d.availabilityMin(), d.restMin(), d.dailyRestMin(), d.extDrivingInWeek(),
                d.reducedRestInWeek(), d.weeklyDrivingMin(), d.biweeklyDrivingMin(), d.remainingDailyMin(),
                d.remainingWeeklyMin(), d.nextWeeklyRestBy(), perDay.getOrDefault(d.day(), 0))).toList();
        Fleet fleet = fleet();
        List<HosDto.Interval> intervals = new ArrayList<>();
        for (Row r : rows) {
            if (!r.end().isAfter(start) || !r.start().isBefore(end)) {
                continue;
            }
            Instant s = r.start().isBefore(start) ? start : r.start();
            Instant e = r.end().isAfter(end) ? end : r.end();
            intervals.add(new HosDto.Interval(s, e, r.activity(), r.slot(), fleet.ref(r.plate(), r.vin())));
        }
        return new HosDto.Activity(new HosDto.Ref(driver.getId(), driver.getFullName()), zone.getId(), days,
                intervals);
    }

    /**
     * Daily tachograph figures of a driver for other modules (the working-day calendar): no privilege check here,
     * the caller has checked its own. Days are cut in the firma's time zone.
     */
    public List<HosEngine.Day> dailyFigures(Long firmaId, Driver driver, LocalDate from, LocalDate to) {
        ZoneId zone = zone(firmaId);
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        List<Row> rows = timeline(firmaId, driver, start.minus(Duration.ofDays(LOOKBACK_DAYS)), end);
        return HosEngine.run(rows.stream().map(Row::seg).toList(), zone).days(from, to);
    }

    /** The recorded segments of a driver within [from, to) for other modules (trip calculator); no privilege check. */
    public List<HosEngine.Seg> recorded(Long firmaId, Driver driver, Instant from, Instant to) {
        return timeline(firmaId, driver, from, to).stream().map(Row::seg).toList();
    }

    // --- violations ------------------------------------------------------------------------------------------

    @RequiresPrivilege({Privileges.TACHO_HOS_VIEW, Privileges.DRIVER_APP_SELF_USE})
    @Transactional(readOnly = true)
    public HosDto.Page violations(Long driverId, LocalDate from, LocalDate to, String severity, boolean unacknowledged,
                                  int page, int size) {
        Long firmaId = InputFields.requireFirmaId();
        self.check(Privileges.TACHO_HOS_VIEW, driverId);
        Map<String, String> errors = new LinkedHashMap<>();
        DriverViolation.Severity s = InputFields.parseEnum(DriverViolation.Severity.class, "severity", severity,
                false, errors);
        if (page < 0) {
            errors.put("page", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (size < 1 || size > 200) {
            errors.put("size", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        ZoneId zone = zone(firmaId);
        Instant fromAt = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant toAt = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        org.springframework.data.domain.Page<DriverViolation> result = violations.search(driverId, s, unacknowledged,
                fromAt, toAt, PageRequest.of(page, size));
        return new HosDto.Page(toDtos(result.getContent()), result.getTotalElements());
    }

    @RequiresPrivilege(Privileges.TACHO_HOS_VIEW)
    @Transactional(readOnly = true)
    public HosDto.Violation violation(Long id) {
        return toDtos(List.of(load(id))).getFirst();
    }

    @RequiresPrivilege(Privileges.TACHO_VIOLATION_ACK)
    @Transactional
    public HosDto.Violation ack(Long id, HosDto.AckInput input) {
        DriverViolation v = load(id);
        String note = input == null ? null : InputFields.blankToNull(input.note());
        if (note != null && note.length() > 500) {
            throw ApiException.validation(Map.of("note", ErrorCodes.FIELD_TOO_LONG));
        }
        v.setAckBy(CurrentUser.require().id());
        v.setAckAt(Instant.now());
        v.setAckNote(note);
        violations.save(v);
        audit.record(AuditEvent.of(MODULE, "violation_acknowledged", "Reviewed violation " + v.getRule().name())
                .entity("driver_violation", v.getId())
                .detail("driverId", v.getDriverId()));
        return toDtos(List.of(v)).getFirst();
    }

    @RequiresPrivilege(Privileges.TACHO_VIOLATION_ACK)
    @Transactional
    public HosDto.Violation unack(Long id) {
        DriverViolation v = load(id);
        v.setAckBy(null);
        v.setAckAt(null);
        v.setAckNote(null);
        violations.save(v);
        audit.record(AuditEvent.of(MODULE, "violation_unacknowledged", "Reopened violation " + v.getRule().name())
                .entity("driver_violation", v.getId())
                .detail("driverId", v.getDriverId()));
        return toDtos(List.of(v)).getFirst();
    }

    // --- recomputation ---------------------------------------------------------------------------------------

    /**
     * Runs the rules on the whole timeline of each driver and brings {@code driver_violation} in line: found
     * violations are inserted or updated (an acknowledgement stays), the others deleted. Called by the import.
     */
    public void recompute(Long firmaId, Collection<Long> driverIds) {
        ZoneId zone = zone(firmaId);
        for (Long driverId : new LinkedHashSet<>(driverIds)) {
            Driver driver = driverId == null ? null : drivers.findById(driverId)
                    .filter(d -> firmaId.equals(d.getFirmaId())).orElse(null);
            if (driver == null) {
                continue;
            }
            List<Row> rows = timeline(firmaId, driver, null, null);
            List<HosEngine.Found> found = HosEngine.run(rows.stream().map(Row::seg).toList(), zone).violations();
            Map<String, DriverViolation> existing = new HashMap<>();
            for (DriverViolation v : violations.findByDriverId(driverId)) {
                existing.put(key(v.getRule(), v.getStartAt()), v);
            }
            for (HosEngine.Found f : found) {
                DriverViolation v = existing.remove(key(f.rule(), f.start()));
                if (v == null) {
                    v = new DriverViolation();
                    v.setFirmaId(firmaId);
                    v.setDriverId(driverId);
                    v.setRule(f.rule());
                    v.setStartAt(f.start());
                }
                v.setSeverity(f.severity());
                v.setEndAt(f.end());
                v.setActualValue(f.actual());
                v.setLimitValue(f.limit());
                v.setVehiclePlate(f.plate());
                v.setDetails(f.details());
                violations.save(v);
            }
            violations.deleteAll(existing.values());
            violations.flush();
        }
    }

    /** Drivers whose timeline uses one of these card numbers (by driver identification). */
    public Set<Long> driversWithCards(Long firmaId, Collection<String> cardNumbers) {
        Set<String> identifications = cardNumbers.stream().filter(Objects::nonNull).map(HosService::identification)
                .collect(Collectors.toSet());
        if (identifications.isEmpty()) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        for (DriverCard c : cards.findAll()) {
            if (firmaId.equals(c.getFirmaId()) && identifications.contains(identification(c.getCardNumber()))) {
                result.add(c.getDriverId());
            }
        }
        result.addAll(jdbc.queryForList("select distinct driver_id from tacho_file where firma_id = :firma"
                        + " and kind = 'DRIVER_CARD' and driver_id is not null and left(card_number, 14) in (:ids)",
                new MapSqlParameterSource("firma", firmaId).addValue("ids", identifications), Long.class));
        return result;
    }

    // --- timeline --------------------------------------------------------------------------------------------

    /** The merged intervals of a driver within [from, to) (null = unbounded), sorted by start. */
    List<Row> timeline(Long firmaId, Driver driver, Instant from, Instant to) {
        MapSqlParameterSource p = new MapSqlParameterSource("firma", firmaId).addValue("driver", driver.getId());
        String range = "";
        if (from != null) {
            range += " and a.end_at > :from";
            p.addValue("from", Timestamp.from(from));
        }
        if (to != null) {
            range += " and a.start_at < :to";
            p.addValue("to", Timestamp.from(to));
        }
        String select = "select a.start_at, a.end_at, a.activity, a.slot, a.vehicle_plate, a.vehicle_vin, a.file_id,"
                + " f.downloaded_at, a.source from tacho_activity a join tacho_file f on f.id = a.file_id"
                + " where a.firma_id = :firma and f.firma_id = :firma";
        List<Row> cardRows = jdbc.query(select + " and f.kind = 'DRIVER_CARD' and f.driver_id = :driver" + range,
                p, (rs, i) -> row(rs));
        Set<String> identifications = identifications(firmaId, driver);
        List<Row> vuRows = List.of();
        if (!identifications.isEmpty()) {
            p.addValue("ids", identifications);
            vuRows = jdbc.query(select + " and f.kind = 'VEHICLE_UNIT' and left(a.card_number, 14) in (:ids)" + range,
                    p, (rs, i) -> row(rs));
        }
        return merge(cardRows, vuRows);
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp downloaded = rs.getTimestamp("downloaded_at");
        return new Row(rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
                rs.getString("activity"), rs.getInt("slot"), rs.getString("vehicle_plate"), rs.getString("vehicle_vin"),
                rs.getLong("file_id"), downloaded == null ? Instant.EPOCH : downloaded.toInstant(),
                TachoFacts.CARD.equals(rs.getString("source")));
    }

    /** Card days win over vehicle unit data; for the same day (card) or moment (unit) the newest file wins. */
    static List<Row> merge(List<Row> cardRows, List<Row> vuRows) {
        Comparator<Row> newest = Comparator.comparing(Row::downloadedAt).thenComparing(Row::fileId);
        Map<LocalDate, Row> bestCardFile = new HashMap<>();
        for (Row r : cardRows) {
            bestCardFile.merge(day(r), r, (a, b) -> newest.compare(a, b) >= 0 ? a : b);
        }
        List<Row> out = new ArrayList<>();
        for (Row r : cardRows) {
            if (bestCardFile.get(day(r)).fileId() == r.fileId()) {
                out.add(r);
            }
        }
        Map<Instant, Row> vuByStart = new HashMap<>();
        for (Row r : vuRows) {
            if (!bestCardFile.containsKey(day(r))) {
                vuByStart.merge(r.start(), r, (a, b) -> newest.compare(a, b) >= 0 ? a : b);
            }
        }
        out.addAll(vuByStart.values());
        out.sort(Comparator.comparing(Row::start).thenComparing(Row::end));
        return out;
    }

    private static LocalDate day(Row r) {
        return r.start().atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** First 14 characters of the driver's card numbers: the current card and those of their card files. */
    private Set<String> identifications(Long firmaId, Driver driver) {
        Set<String> ids = new LinkedHashSet<>();
        for (DriverCard c : cards.findByDriverIdOrderByIdDesc(driver.getId())) {
            ids.add(identification(c.getCardNumber()));
        }
        ids.addAll(jdbc.queryForList("select distinct left(card_number, 14) from tacho_file where firma_id = :firma"
                        + " and kind = 'DRIVER_CARD' and driver_id = :driver and card_number is not null",
                new MapSqlParameterSource("firma", firmaId).addValue("driver", driver.getId()), String.class));
        ids.remove(null);
        return ids;
    }

    static String identification(String cardNumber) {
        return cardNumber.length() > 14 ? cardNumber.substring(0, 14) : cardNumber;
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private DriverViolation load(Long id) {
        Long firmaId = InputFields.requireFirmaId();
        return violations.findById(id).filter(v -> firmaId.equals(v.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private List<HosDto.Violation> toDtos(List<DriverViolation> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Driver> driverOf = drivers.findAllById(list.stream().map(DriverViolation::getDriverId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Driver::getId, Function.identity()));
        Map<Long, AppUser> userOf = users.findAllById(list.stream().map(DriverViolation::getAckBy)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
        Fleet fleet = fleet();
        return list.stream().map(v -> {
            Driver d = driverOf.get(v.getDriverId());
            AppUser by = v.getAckBy() == null ? null : userOf.get(v.getAckBy());
            HosDto.Ack ack = v.getAckAt() == null ? null : new HosDto.Ack(
                    by == null ? null : new HosDto.Ref(by.getId(), by.getFullName().trim()), v.getAckAt(),
                    v.getAckNote());
            return new HosDto.Violation(v.getId(), new HosDto.Ref(v.getDriverId(), d == null ? null : d.getFullName()),
                    v.getRule(), v.getSeverity(), v.getStartAt(), v.getEndAt(), v.getActualValue(), v.getLimitValue(),
                    fleet.ref(v.getVehiclePlate(), null), v.getDetails(), ack);
        }).toList();
    }

    private ZoneId zone(Long firmaId) {
        String id = firmas.findById(firmaId).map(f -> f.getTimezone()).orElse("UTC");
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static String key(DriverViolation.Rule rule, Instant start) {
        return rule.name() + "|" + start.truncatedTo(ChronoUnit.SECONDS);
    }

    /** The fleet's non-deleted vehicles by VIN and plate, to turn plates of files into vehicle references. */
    private Fleet fleet() {
        Map<String, Vehicle> byVin = new HashMap<>();
        Map<String, Vehicle> byPlate = new HashMap<>();
        for (Vehicle v : vehicles.findByDeletedFalseOrderByPlateAsc()) {
            if (v.getVin() != null) {
                byVin.putIfAbsent(v.getVin(), v);
            }
            byPlate.putIfAbsent(VehicleService.plateKey(v.getPlate()), v);
        }
        return new Fleet(byVin, byPlate);
    }

    private record Fleet(Map<String, Vehicle> byVin, Map<String, Vehicle> byPlate) {

        HosDto.VehicleRef ref(String plate, String vin) {
            Vehicle v = vin == null ? null : byVin.get(vin);
            if (v == null && plate != null) {
                v = byPlate.get(VehicleService.plateKey(plate));
            }
            if (v != null) {
                return new HosDto.VehicleRef(v.getId(), v.getPlate());
            }
            return plate == null ? null : new HosDto.VehicleRef(null, plate);
        }
    }
}

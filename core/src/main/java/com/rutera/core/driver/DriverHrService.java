package com.rutera.core.driver;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Driver;
import com.rutera.common.domain.DriverAbsence;
import com.rutera.common.domain.DriverDay;
import com.rutera.common.domain.DriverPayRate;
import com.rutera.common.domain.DriverPayment;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.DriverAbsenceRepository;
import com.rutera.common.repository.DriverDayRepository;
import com.rutera.common.repository.DriverPayRateRepository;
import com.rutera.common.repository.DriverPaymentRepository;
import com.rutera.common.repository.DriverRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.tacho.HosService;
import com.rutera.core.tacho.hos.HosEngine;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Driver HR ({@code /app/drivers/{id}/absences|days|pay-rates|payments}, {@code /app/absences}, {@code /app/payments}):
 * absences, the working-day calendar, pay rates and payments. Absences may not overlap; the calendar is computed
 * from the tachograph data and the absences, with manual entries on top; payments are visible to the payment
 * privileges only (owner and accountant by default).
 */
@Service
public class DriverHrService {

    public static final String MODULE = "driver";
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final int MAX_ABSENCE_DAYS = 366;

    private final DriverRepository drivers;
    private final DriverAbsenceRepository absences;
    private final DriverDayRepository days;
    private final DriverPayRateRepository rates;
    private final DriverPaymentRepository payments;
    private final AppUserRepository users;
    private final HosService hos;
    private final DriverSelf self;
    private final FirmaRepository firmas;
    private final AuditService audit;

    public DriverHrService(DriverRepository drivers, DriverAbsenceRepository absences, DriverDayRepository days,
                           DriverPayRateRepository rates, DriverPaymentRepository payments, AppUserRepository users,
                           HosService hos, DriverSelf self, FirmaRepository firmas, AuditService audit) {
        this.self = self;
        this.firmas = firmas;
        this.drivers = drivers;
        this.absences = absences;
        this.days = days;
        this.rates = rates;
        this.payments = payments;
        this.users = users;
        this.hos = hos;
        this.audit = audit;
    }

    // --- absences ------------------------------------------------------------------------------------------

    @RequiresPrivilege({Privileges.DRIVER_VIEW, Privileges.DRIVER_APP_SELF_USE})
    @Transactional(readOnly = true)
    public List<HrDto.Absence> absences(Long driverId, LocalDate from, LocalDate to, String status) {
        self.check(Privileges.DRIVER_VIEW, driverId);
        DriverAbsence.Status s = status(status);
        Driver driver = load(driverId);
        return toAbsences(absences.search(driver.getId(), null, from, to).stream()
                .filter(a -> s == null || a.getStatus() == s).toList());
    }

    private static DriverAbsence.Status status(String raw) {
        Map<String, String> errors = new LinkedHashMap<>();
        DriverAbsence.Status s = InputFields.parseEnum(DriverAbsence.Status.class, "status", raw, false, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return s;
    }

    /** Absences of the whole firma overlapping [from, to], for the "who is away" calendar. */
    @RequiresPrivilege(Privileges.DRIVER_VIEW)
    @Transactional(readOnly = true)
    public List<HrDto.Absence> allAbsences(LocalDate from, LocalDate to, String kind, Long driverId, String status) {
        InputFields.requireFirmaId();
        Map<String, String> errors = new LinkedHashMap<>();
        DriverAbsence.Kind k = InputFields.parseEnum(DriverAbsence.Kind.class, "kind", kind, false, errors);
        DriverAbsence.Status s = InputFields.parseEnum(DriverAbsence.Status.class, "status", status, false, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return toAbsences(absences.search(driverId, k, from, to).stream()
                .filter(a -> s == null || a.getStatus() == s).toList());
    }

    /** By the office: APPROVED at once; by the driver itself (cabinet): a REQUESTED absence for the office to decide. */
    @RequiresPrivilege({Privileges.DRIVER_ABSENCE_MANAGE, Privileges.DRIVER_APP_SELF_USE})
    @Transactional
    public HrDto.Absence createAbsence(Long driverId, HrDto.AbsenceInput input) {
        boolean office = self.full(Privileges.DRIVER_ABSENCE_MANAGE);
        if (!office) {
            self.requireOwn(driverId);
        }
        Driver driver = load(driverId);
        DriverAbsence a = new DriverAbsence();
        a.setFirmaId(driver.getFirmaId());
        a.setDriverId(driver.getId());
        apply(a, input);
        if (office) {
            a.setStatus(DriverAbsence.Status.APPROVED);
            a.setApprovedBy(CurrentUser.require().id());
            a.setApprovedAt(Instant.now());
        } else {
            a.setStatus(DriverAbsence.Status.REQUESTED);
        }
        a = absences.save(a);
        audit.record(AuditEvent.of(MODULE, "absence_created", "Added " + a.getKind().name() + " of driver "
                + driver.getFullName()).entity("driver_absence", a.getId()).detail("driverId", driver.getId()));
        return toAbsences(List.of(a)).getFirst();
    }

    @RequiresPrivilege(Privileges.DRIVER_ABSENCE_MANAGE)
    @Transactional
    public HrDto.Absence updateAbsence(Long driverId, Long absenceId, HrDto.AbsenceInput input) {
        Driver driver = load(driverId);
        DriverAbsence a = loadAbsence(driver, absenceId);
        apply(a, input);
        a = absences.save(a);
        audit.record(AuditEvent.of(MODULE, "absence_updated", "Updated " + a.getKind().name() + " of driver "
                + driver.getFullName()).entity("driver_absence", a.getId()).detail("driverId", driver.getId()));
        return toAbsences(List.of(a)).getFirst();
    }

    /** The office decides on a request (or changes its decision): APPROVED or REJECTED, with an optional note. */
    @RequiresPrivilege(Privileges.DRIVER_ABSENCE_MANAGE)
    @Transactional
    public HrDto.Absence decideAbsence(Long driverId, Long absenceId, HrDto.AbsenceDecision decision) {
        Driver driver = load(driverId);
        DriverAbsence a = loadAbsence(driver, absenceId);
        Map<String, String> errors = new LinkedHashMap<>();
        DriverAbsence.Status s = InputFields.parseEnum(DriverAbsence.Status.class, "status",
                decision == null ? null : decision.status(), true, errors);
        if (s == DriverAbsence.Status.REQUESTED) {
            errors.put("status", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (s == DriverAbsence.Status.APPROVED && absences.search(a.getDriverId(), null, a.getFromDate(),
                a.getToDate()).stream().anyMatch(o -> !o.getId().equals(a.getId())
                && o.getStatus() != DriverAbsence.Status.REJECTED)) {
            errors.put("status", ErrorCodes.ABSENCE_OVERLAP);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        a.setStatus(s);
        a.setDecisionNote(InputFields.blankToNull(decision.note()));
        a.setApprovedBy(CurrentUser.require().id());
        a.setApprovedAt(Instant.now());
        DriverAbsence saved = absences.save(a);
        audit.record(AuditEvent.of(MODULE, "absence_decided", s.name() + " " + saved.getKind().name() + " of driver "
                + driver.getFullName()).entity("driver_absence", saved.getId()).detail("driverId", driver.getId()));
        return toAbsences(List.of(saved)).getFirst();
    }

    /** By the office; the driver may withdraw its own request while it is REQUESTED (else 409). */
    @RequiresPrivilege({Privileges.DRIVER_ABSENCE_MANAGE, Privileges.DRIVER_APP_SELF_USE})
    @Transactional
    public void deleteAbsence(Long driverId, Long absenceId) {
        boolean office = self.full(Privileges.DRIVER_ABSENCE_MANAGE);
        if (!office) {
            self.requireOwn(driverId);
        }
        Driver driver = load(driverId);
        DriverAbsence a = loadAbsence(driver, absenceId);
        if (!office && a.getStatus() != DriverAbsence.Status.REQUESTED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        absences.delete(a);
        audit.record(AuditEvent.of(MODULE, "absence_deleted", "Deleted " + a.getKind().name() + " of driver "
                + driver.getFullName()).entity("driver_absence", a.getId()).detail("driverId", driver.getId()));
    }

    private void apply(DriverAbsence a, HrDto.AbsenceInput in) {
        Map<String, String> errors = new LinkedHashMap<>();
        DriverAbsence.Kind kind = InputFields.parseEnum(DriverAbsence.Kind.class, "kind", in.kind(), true, errors);
        if (in.fromDate() == null) {
            errors.put("fromDate", ErrorCodes.FIELD_REQUIRED);
        }
        if (in.toDate() == null) {
            errors.put("toDate", ErrorCodes.FIELD_REQUIRED);
        } else if (in.fromDate() != null) {
            if (in.toDate().isBefore(in.fromDate())
                    || ChronoUnit.DAYS.between(in.fromDate(), in.toDate()) >= MAX_ABSENCE_DAYS) {
                errors.put("toDate", ErrorCodes.FIELD_INVALID_VALUE);
            } else if (absences.search(a.getDriverId(), null, in.fromDate(), in.toDate()).stream()
                    .anyMatch(o -> !o.getId().equals(a.getId()) && o.getStatus() != DriverAbsence.Status.REJECTED)) {
                errors.put("toDate", ErrorCodes.ABSENCE_OVERLAP);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        a.setKind(kind);
        a.setFromDate(in.fromDate());
        a.setToDate(in.toDate());
        a.setDays((int) ChronoUnit.DAYS.between(in.fromDate(), in.toDate()) + 1);
        a.setPaid(in.paid() != null ? in.paid() : kind != DriverAbsence.Kind.UNPAID);
        a.setNote(InputFields.blankToNull(in.note()));
    }

    private DriverAbsence loadAbsence(Driver driver, Long id) {
        return absences.findById(id).filter(a -> a.getDriverId().equals(driver.getId())
                        && driver.getFirmaId().equals(a.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private List<HrDto.Absence> toAbsences(List<DriverAbsence> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Driver> driverOf = byId(drivers.findAllById(ids(list, DriverAbsence::getDriverId)), Driver::getId);
        Map<Long, AppUser> userOf = byId(users.findAllById(ids(list, DriverAbsence::getApprovedBy)), AppUser::getId);
        return list.stream().map(a -> new HrDto.Absence(a.getId(), ref(driverOf.get(a.getDriverId()), a.getDriverId()),
                a.getKind(), a.getFromDate(), a.getToDate(), a.getDays(), workingDays(a.getFromDate(), a.getToDate()),
                a.isPaid(), a.getNote(), a.getApprovedBy() == null ? null : user(userOf.get(a.getApprovedBy())),
                a.getApprovedAt(), a.getCreatedAt(), a.getUpdatedAt(), a.getStatus(), a.getDecisionNote())).toList();
    }

    /** Monday to Friday, both ends included. */
    static int workingDays(LocalDate from, LocalDate to) {
        int n = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
            }
        }
        return n;
    }

    // --- working days --------------------------------------------------------------------------------------

    @RequiresPrivilege({Privileges.DRIVER_VIEW, Privileges.DRIVER_APP_SELF_USE})
    @Transactional(readOnly = true)
    public HrDto.Month month(Long driverId, String month) {
        self.check(Privileges.DRIVER_VIEW, driverId);
        Driver driver = load(driverId);
        YearMonth ym = parseMonth("month", month);
        List<HrDto.Day> list = calendar(driver, ym.atDay(1), ym.atEndOfMonth());
        int work = 0;
        int rest = 0;
        int absence = 0;
        int holiday = 0;
        int driving = 0;
        int workMin = 0;
        for (HrDto.Day d : list) {
            switch (d.kind()) {
                case WORK -> work++;
                case REST -> rest++;
                case ABSENCE -> absence++;
                case HOLIDAY -> holiday++;
            }
            driving += d.drivingMin();
            workMin += d.workMin();
        }
        return new HrDto.Month(list, new HrDto.Summary(work, rest, absence, holiday, driving, workMin));
    }

    @RequiresPrivilege(Privileges.DRIVER_MANAGE)
    @Transactional
    public HrDto.Day setDay(Long driverId, LocalDate day, HrDto.DayInput input) {
        Driver driver = load(driverId);
        Map<String, String> errors = new LinkedHashMap<>();
        DriverDay.Kind kind = InputFields.parseEnum(DriverDay.Kind.class, "kind", input == null ? null : input.kind(),
                true, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        DriverDay entry = days.findByDriverIdAndDay(driver.getId(), day).orElseGet(() -> {
            DriverDay d = new DriverDay();
            d.setFirmaId(driver.getFirmaId());
            d.setDriverId(driver.getId());
            d.setDay(day);
            return d;
        });
        entry.setKind(kind);
        entry.setNote(InputFields.blankToNull(input.note()));
        days.saveAndFlush(entry);
        audit.record(AuditEvent.of(MODULE, "day_set", "Set " + day + " of driver " + driver.getFullName() + " to "
                + kind.name()).entity("driver_day", entry.getId()).detail("driverId", driver.getId()));
        return calendar(driver, day, day).getFirst();
    }

    @RequiresPrivilege(Privileges.DRIVER_MANAGE)
    @Transactional
    public HrDto.Day clearDay(Long driverId, LocalDate day) {
        Driver driver = load(driverId);
        days.findByDriverIdAndDay(driver.getId(), day).ifPresent(entry -> {
            days.delete(entry);
            days.flush();
            audit.record(AuditEvent.of(MODULE, "day_cleared", "Cleared " + day + " of driver " + driver.getFullName())
                    .entity("driver_day", entry.getId()).detail("driverId", driver.getId()));
        });
        return calendar(driver, day, day).getFirst();
    }

    /** Manual entry, else absence, else the tachograph (driving or work = WORK, recorded rest only = REST). */
    private List<HrDto.Day> calendar(Driver driver, LocalDate from, LocalDate to) {
        Map<LocalDate, DriverDay> manual = days.findByDriverIdAndDayBetween(driver.getId(), from, to).stream()
                .collect(Collectors.toMap(DriverDay::getDay, Function.identity()));
        List<DriverAbsence> away = absences.search(driver.getId(), null, from, to).stream()
                .filter(a -> a.getStatus() == DriverAbsence.Status.APPROVED).toList();
        Map<LocalDate, HosEngine.Day> tacho = new HashMap<>();
        for (HosEngine.Day d : hos.dailyFigures(driver.getFirmaId(), driver, from, to)) {
            tacho.put(d.day(), d);
        }
        List<HrDto.Day> result = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            HosEngine.Day t = tacho.get(d);
            int driving = t == null ? 0 : t.drivingMin();
            int work = t == null ? 0 : t.workMin();
            boolean recorded = t != null && (t.drivingMin() + t.workMin() + t.availabilityMin() + t.restMin()) > 0;
            LocalDate day = d;
            DriverAbsence absence = away.stream()
                    .filter(a -> !day.isBefore(a.getFromDate()) && !day.isAfter(a.getToDate())).findFirst()
                    .orElse(null);
            DriverDay entry = manual.get(d);
            if (entry != null) {
                result.add(new HrDto.Day(d, entry.getKind(), HrDto.DaySource.MANUAL,
                        absence == null ? null : absence.getKind(), driving, work, entry.getNote()));
            } else if (absence != null) {
                result.add(new HrDto.Day(d, DriverDay.Kind.ABSENCE, HrDto.DaySource.ABSENCE, absence.getKind(),
                        driving, work, null));
            } else if (driving + work > 0) {
                result.add(new HrDto.Day(d, DriverDay.Kind.WORK, HrDto.DaySource.TACHO, null, driving, work, null));
            } else {
                result.add(new HrDto.Day(d, DriverDay.Kind.REST, recorded ? HrDto.DaySource.TACHO
                        : HrDto.DaySource.NONE, null, driving, work, null));
            }
        }
        return result;
    }

    // --- pay rates -----------------------------------------------------------------------------------------

    @RequiresPrivilege({Privileges.DRIVER_PAYMENT_VIEW, Privileges.DRIVER_APP_SELF_USE})
    @Transactional(readOnly = true)
    public List<HrDto.PayRate> rates(Long driverId) {
        ownPay(driverId);
        Driver driver = load(driverId);
        return rates.findByDriverIdOrderByValidFromDesc(driver.getId()).stream().map(DriverHrService::toRate).toList();
    }

    @RequiresPrivilege(Privileges.DRIVER_PAYMENT_MANAGE)
    @Transactional
    public HrDto.PayRate createRate(Long driverId, HrDto.PayRateInput input) {
        Driver driver = load(driverId);
        DriverPayRate r = new DriverPayRate();
        r.setFirmaId(driver.getFirmaId());
        r.setDriverId(driver.getId());
        apply(r, input);
        r = saveRate(r);
        audit.record(AuditEvent.of(MODULE, "pay_rate_created", "Added a pay rate of driver " + driver.getFullName())
                .entity("driver_pay_rate", r.getId()).detail("driverId", driver.getId()));
        return toRate(r);
    }

    @RequiresPrivilege(Privileges.DRIVER_PAYMENT_MANAGE)
    @Transactional
    public HrDto.PayRate updateRate(Long driverId, Long rateId, HrDto.PayRateInput input) {
        Driver driver = load(driverId);
        DriverPayRate r = rates.findById(rateId).filter(x -> x.getDriverId().equals(driver.getId())
                && driver.getFirmaId().equals(x.getFirmaId())).orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
        apply(r, input);
        r = saveRate(r);
        audit.record(AuditEvent.of(MODULE, "pay_rate_updated", "Updated a pay rate of driver " + driver.getFullName())
                .entity("driver_pay_rate", r.getId()).detail("driverId", driver.getId()));
        return toRate(r);
    }

    @RequiresPrivilege(Privileges.DRIVER_PAYMENT_MANAGE)
    @Transactional
    public void deleteRate(Long driverId, Long rateId) {
        Driver driver = load(driverId);
        DriverPayRate r = rates.findById(rateId).filter(x -> x.getDriverId().equals(driver.getId())
                && driver.getFirmaId().equals(x.getFirmaId())).orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
        rates.delete(r);
        audit.record(AuditEvent.of(MODULE, "pay_rate_deleted", "Deleted a pay rate of driver " + driver.getFullName())
                .entity("driver_pay_rate", r.getId()).detail("driverId", driver.getId()));
    }

    private void apply(DriverPayRate r, HrDto.PayRateInput in) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (in.validFrom() == null) {
            errors.put("validFrom", ErrorCodes.FIELD_REQUIRED);
        } else if (rates.findByDriverIdAndValidFrom(r.getDriverId(), in.validFrom())
                .filter(o -> !o.getId().equals(r.getId())).isPresent()) {
            errors.put("validFrom", ErrorCodes.PAY_RATE_DATE_TAKEN);
        }
        if (in.monthlyBase() == null && in.perDiemHome() == null && in.perDiemAbroad() == null && in.perKm() == null) {
            errors.put("monthlyBase", ErrorCodes.FIELD_REQUIRED);
        }
        String currency = currency("currency", in.currency(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        r.setValidFrom(in.validFrom());
        r.setMonthlyBase(scale(in.monthlyBase(), 2));
        r.setPerDiemHome(scale(in.perDiemHome(), 2));
        r.setPerDiemAbroad(scale(in.perDiemAbroad(), 2));
        r.setPerKm(scale(in.perKm(), 4));
        r.setCurrency(currency);
    }

    private DriverPayRate saveRate(DriverPayRate r) {
        try {
            return rates.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.validation(Map.of("validFrom", ErrorCodes.PAY_RATE_DATE_TAKEN));
        }
    }

    private static HrDto.PayRate toRate(DriverPayRate r) {
        return new HrDto.PayRate(r.getId(), r.getValidFrom(), r.getMonthlyBase(), r.getPerDiemHome(),
                r.getPerDiemAbroad(), r.getPerKm(), r.getCurrency(), r.getCreatedAt(), r.getUpdatedAt());
    }

    // --- payments ------------------------------------------------------------------------------------------

    @RequiresPrivilege({Privileges.DRIVER_PAYMENT_VIEW, Privileges.DRIVER_APP_SELF_USE})
    @Transactional(readOnly = true)
    public List<HrDto.Payment> payments(Long driverId, String from, String to) {
        ownPay(driverId);
        Driver driver = load(driverId);
        String f = from == null || from.isBlank() ? null : parseMonth("from", from).toString();
        String t = to == null || to.isBlank() ? null : parseMonth("to", to).toString();
        return toPayments(payments.search(driver.getId(), null, f, t));
    }

    /** Payments of the firma for one period (or all), with what is owed per currency. */
    @RequiresPrivilege(Privileges.DRIVER_PAYMENT_VIEW)
    @Transactional(readOnly = true)
    public HrDto.PaymentList allPayments(String period, Long driverId, String kind) {
        InputFields.requireFirmaId();
        Map<String, String> errors = new LinkedHashMap<>();
        DriverPayment.Kind k = InputFields.parseEnum(DriverPayment.Kind.class, "kind", kind, false, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        String p = period == null || period.isBlank() ? null : parseMonth("period", period).toString();
        List<DriverPayment> list = payments.search(driverId, k, p, p);
        Map<String, BigDecimal> totals = new TreeMap<>();
        for (DriverPayment x : list) {
            BigDecimal signed = x.getKind().subtracts() ? x.getAmount().negate() : x.getAmount();
            totals.merge(x.getCurrency(), signed, BigDecimal::add);
        }
        return new HrDto.PaymentList(toPayments(list), totals.entrySet().stream()
                .map(e -> new HrDto.Total(e.getKey(), e.getValue().setScale(2, RoundingMode.HALF_UP))).toList());
    }

    @RequiresPrivilege(Privileges.DRIVER_PAYMENT_MANAGE)
    @Transactional
    public HrDto.Payment createPayment(Long driverId, HrDto.PaymentInput input) {
        Driver driver = load(driverId);
        DriverPayment p = new DriverPayment();
        p.setFirmaId(driver.getFirmaId());
        p.setDriverId(driver.getId());
        apply(p, input);
        p = payments.save(p);
        audit.record(AuditEvent.of(MODULE, "payment_created", "Added a " + p.getKind().name() + " payment of driver "
                + driver.getFullName()).entity("driver_payment", p.getId()).detail("driverId", driver.getId()));
        return toPayments(List.of(p)).getFirst();
    }

    @RequiresPrivilege(Privileges.DRIVER_PAYMENT_MANAGE)
    @Transactional
    public HrDto.Payment updatePayment(Long driverId, Long paymentId, HrDto.PaymentInput input) {
        Driver driver = load(driverId);
        DriverPayment p = loadPayment(driver, paymentId);
        apply(p, input);
        p = payments.save(p);
        audit.record(AuditEvent.of(MODULE, "payment_updated", "Updated a " + p.getKind().name() + " payment of driver "
                + driver.getFullName()).entity("driver_payment", p.getId()).detail("driverId", driver.getId()));
        return toPayments(List.of(p)).getFirst();
    }

    @RequiresPrivilege(Privileges.DRIVER_PAYMENT_MANAGE)
    @Transactional
    public void deletePayment(Long driverId, Long paymentId) {
        Driver driver = load(driverId);
        DriverPayment p = loadPayment(driver, paymentId);
        payments.delete(p);
        audit.record(AuditEvent.of(MODULE, "payment_deleted", "Deleted a " + p.getKind().name() + " payment of driver "
                + driver.getFullName()).entity("driver_payment", p.getId()).detail("driverId", driver.getId()));
    }

    private void apply(DriverPayment p, HrDto.PaymentInput in) {
        Map<String, String> errors = new LinkedHashMap<>();
        YearMonth period = null;
        if (in.period() == null || in.period().isBlank()) {
            errors.put("period", ErrorCodes.FIELD_REQUIRED);
        } else {
            try {
                period = YearMonth.parse(in.period().strip());
            } catch (DateTimeParseException e) {
                errors.put("period", ErrorCodes.FIELD_INVALID_FORMAT);
            }
        }
        DriverPayment.Kind kind = InputFields.parseEnum(DriverPayment.Kind.class, "kind", in.kind(), true, errors);
        if (in.amount() == null) {
            errors.put("amount", ErrorCodes.FIELD_REQUIRED);
        }
        String currency = currency("currency", in.currency(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        p.setPeriod(period.toString());
        p.setKind(kind);
        p.setAmount(scale(in.amount(), 2));
        p.setCurrency(currency);
        p.setPaidAt(in.paidAt());
        p.setNote(InputFields.blankToNull(in.note()));
    }

    private DriverPayment loadPayment(Driver driver, Long id) {
        return payments.findById(id).filter(p -> p.getDriverId().equals(driver.getId())
                        && driver.getFirmaId().equals(p.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private List<HrDto.Payment> toPayments(List<DriverPayment> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Driver> driverOf = byId(drivers.findAllById(ids(list, DriverPayment::getDriverId)), Driver::getId);
        return list.stream().map(p -> new HrDto.Payment(p.getId(), ref(driverOf.get(p.getDriverId()), p.getDriverId()),
                p.getPeriod(), p.getKind(), p.getAmount(), p.getCurrency(), p.getPaidAt(), p.getNote(),
                p.getCreatedAt(), p.getUpdatedAt())).toList();
    }

    /** Without driver.payment/view: only the own driver, and only when the firma lets drivers see their pay. */
    private void ownPay(Long driverId) {
        if (self.full(Privileges.DRIVER_PAYMENT_VIEW)) {
            return;
        }
        self.requireOwn(driverId);
        if (!firmas.findById(InputFields.requireFirmaId()).map(f -> f.isDriversSeePay()).orElse(false)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED);
        }
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private Driver load(Long id) {
        Long firmaId = InputFields.requireFirmaId();
        return drivers.findByIdAndDeletedFalse(id).filter(d -> firmaId.equals(d.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private static YearMonth parseMonth(String field, String raw) {
        try {
            return YearMonth.parse(raw == null ? "" : raw.strip());
        } catch (DateTimeParseException e) {
            throw ApiException.validation(Map.of(field, raw == null || raw.isBlank()
                    ? ErrorCodes.FIELD_REQUIRED : ErrorCodes.FIELD_INVALID_FORMAT));
        }
    }

    private static String currency(String field, String raw, Map<String, String> errors) {
        String c = InputFields.blankToNull(raw);
        if (c == null) {
            errors.put(field, ErrorCodes.FIELD_REQUIRED);
            return null;
        }
        c = c.toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(c).matches()) {
            errors.put(field, ErrorCodes.FIELD_INVALID_FORMAT);
            return null;
        }
        return c;
    }

    private static BigDecimal scale(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private static HrDto.Ref ref(Driver d, Long id) {
        return new HrDto.Ref(id, d == null ? null : d.getFullName());
    }

    private static HrDto.Ref user(AppUser u) {
        return u == null ? null : new HrDto.Ref(u.getId(), u.getFullName().trim());
    }

    private static <T> List<Long> ids(List<T> list, Function<T, Long> id) {
        return list.stream().map(id).filter(Objects::nonNull).distinct().toList();
    }

    private static <T> Map<Long, T> byId(Collection<T> items, Function<T, Long> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }
}

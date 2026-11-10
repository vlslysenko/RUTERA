package com.rutera.core.calc;

import com.rutera.common.domain.Driver;
import com.rutera.common.domain.DriverPayRate;
import com.rutera.common.domain.Firma;
import com.rutera.common.domain.FuelTransaction;
import com.rutera.common.domain.TollTariff;
import com.rutera.common.domain.Vehicle;
import com.rutera.common.repository.DriverPayRateRepository;
import com.rutera.common.repository.DriverRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.repository.FuelTransactionRepository;
import com.rutera.common.repository.TollTariffRepository;
import com.rutera.common.repository.VehicleRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.calc.plan.DriverState;
import com.rutera.core.calc.plan.HosPlanner;
import com.rutera.core.fuel.read.FuelValues;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.tacho.HosService;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The trip calculator ({@code POST /app/calc/trip}): breaks, rests and arrival by {@link HosPlanner} from the drivers'
 * tachograph state, and the cost estimate: fuel (consumption x price), driver pay (pay rate valid at the start),
 * tolls (the firma's toll tariffs per country) and other costs. No currency conversion yet: totals per currency.
 */
@Service
public class TripCalcService {

    static final int DEFAULT_SPEED_KMH = 70;
    static final int TACHO_LOOKBACK_DAYS = 35;
    static final int FUEL_PRICE_DAYS = 30;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal THIRTY = BigDecimal.valueOf(30);

    private final FirmaRepository firmas;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final DriverPayRateRepository payRates;
    private final FuelTransactionRepository fuel;
    private final FuelPrices fuelPrices;
    private final TollTariffRepository tariffs;
    private final HosService hos;
    private final RouteService routes;

    public TripCalcService(FirmaRepository firmas, VehicleRepository vehicles, DriverRepository drivers,
                           DriverPayRateRepository payRates, FuelTransactionRepository fuel,
                           TollTariffRepository tariffs, HosService hos, RouteService routes,
                           FuelPrices fuelPrices) {
        this.firmas = firmas;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.payRates = payRates;
        this.fuel = fuel;
        this.tariffs = tariffs;
        this.hos = hos;
        this.routes = routes;
        this.fuelPrices = fuelPrices;
    }

    private record Input(Instant start, Vehicle vehicle, List<Driver> crew, int crewSize, DriverState.Source mode,
                         CalcDto.ManualState manual, List<HosPlanner.Leg> legs, Map<String, BigDecimal> kmByCountry,
                         Map<String, BigDecimal> tollKmByCountry, BigDecimal distance, CalcDto.Options options) {
    }

    /**
     * The cost breakdown of a computed route: the plan of its legs (start: the input's, else now; drivers and options
     * as given, the drivers' tachograph state when there are drivers) reduced to countries, arrival, days and costs.
     */
    @RequiresPrivilege(Privileges.TRIP_TRIP_VIEW)
    @Transactional(readOnly = true)
    public CalcDto.RouteBreakdown breakdown(CalcDto.RouteInput in, CalcDto.Route route) {
        Instant start = in.start() != null ? in.start() : Instant.now().truncatedTo(ChronoUnit.MINUTES);
        CalcDto.TripInput trip = new CalcDto.TripInput(start, in.vehicleId(), in.driverIds(), null, null, null,
                legsOf(in.points(), route), null, in.options());
        CalcDto.Plan plan = plan(trip);
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
        for (CalcDto.RouteLeg leg : route.legs()) {
            for (CalcDto.CountryKm c : leg.countries()) {
                BigDecimal[] sum = sums.computeIfAbsent(c.country(), k -> new BigDecimal[]{BigDecimal.ZERO, null});
                sum[0] = sum[0].add(c.km());
                if (c.tollKm() != null) {
                    sum[1] = sum[1] == null ? c.tollKm() : sum[1].add(c.tollKm());
                }
            }
        }
        List<CalcDto.CountryKm> countries = sums.entrySet().stream()
                .map(e -> new CalcDto.CountryKm(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        return new CalcDto.RouteBreakdown(countries, plan.eta(), plan.totals().days(), plan.costs(), plan.payHidden());
    }

    @RequiresPrivilege(Privileges.TRIP_TRIP_VIEW)
    @Transactional(readOnly = true)
    public CalcDto.Plan plan(CalcDto.TripInput in) {
        Long firmaId = InputFields.requireFirmaId();
        Firma firma = firmas.findById(firmaId).orElseThrow();
        ZoneId zone = zone(firma);
        Input input = validate(in, firma);
        List<String> warnings = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        List<DriverState> states = new ArrayList<>();
        List<CalcDto.DriverState> stateDtos = new ArrayList<>();
        for (int i = 0; i < input.crewSize(); i++) {
            Driver driver = i < input.crew().size() ? input.crew().get(i) : null;
            DriverState s = state(input, driver, zone, firmaId, warnings, missing);
            states.add(s);
            stateDtos.add(new CalcDto.DriverState(driver == null ? null : driver.getId(), s.source().name(),
                    s.dataUntil(), s.drivingSinceBreakMin(), s.drivingInShiftMin(), s.shiftStart(),
                    s.weekDrivingMin(), s.fortnightDrivingMin(), s.extensionsLeft(), s.reducedRestsLeft(),
                    s.weeklyRestDueAt()));
        }
        CalcDto.Options o = input.options();
        HosPlanner.Result result = HosPlanner.plan(input.start(), input.legs(), states, new HosPlanner.Options(
                !Boolean.FALSE.equals(o.extendedDriving()), !Boolean.FALSE.equals(o.reducedRests()),
                Boolean.TRUE.equals(o.reducedWeeklyRest())));

        int driving = 0;
        int breaks = 0;
        int rests = 0;
        int work = 0;
        int dailyRests = 0;
        int weeklyRests = 0;
        List<CalcDto.Event> timeline = new ArrayList<>();
        Map<Integer, BigDecimal> kmByDriver = new TreeMap<>();
        for (HosPlanner.Event e : result.events()) {
            switch (e.kind()) {
                case DRIVE -> {
                    driving += e.minutes();
                    kmByDriver.merge(e.driver() == null ? 1 : e.driver(),
                            BigDecimal.valueOf(e.kmTo() - e.kmFrom()), BigDecimal::add);
                }
                case BREAK -> breaks += e.minutes();
                case SERVICE -> work += e.minutes();
                case DAILY_REST, REDUCED_DAILY_REST -> {
                    rests += e.minutes();
                    dailyRests++;
                }
                case WEEKLY_REST, REDUCED_WEEKLY_REST -> {
                    rests += e.minutes();
                    weeklyRests++;
                }
            }
            timeline.add(new CalcDto.Event(e.kind().name(), e.start(), e.end(), e.minutes(), km(e.kmFrom()),
                    km(e.kmTo()), e.legIndex(), e.driver()));
        }
        if (weeklyRests > 0) {
            warnings.add("calc.warning.weekly_rest_needed");
        }
        long duration = Duration.between(input.start(), result.eta()).toMinutes();
        int days = (int) Math.max(1, (duration + 1439) / 1440);
        CalcDto.Totals totals = new CalcDto.Totals(input.distance().setScale(1, RoundingMode.HALF_UP), driving,
                breaks, rests, work, duration, dailyRests, weeklyRests, days);

        boolean payHidden = !CurrentUser.require().hasPrivilege(Privileges.DRIVER_PAYMENT_VIEW);
        CalcDto.Costs costs = costs(input, firma, days, kmByDriver, payHidden, missing);
        return new CalcDto.Plan(input.start(), result.eta(), totals, timeline, stateDtos, costs, payHidden,
                List.copyOf(new LinkedHashSet<>(warnings)));
    }

    private DriverState state(Input input, Driver driver, ZoneId zone, Long firmaId, List<String> warnings,
                              List<String> missing) {
        Instant start = input.start();
        if (input.mode() == DriverState.Source.MANUAL) {
            return manual(input, zone);
        }
        if (input.mode() == DriverState.Source.TACHO && driver != null) {
            DriverState s = DriverState.fromRecorded(hos.recorded(firmaId, driver,
                    start.minus(Duration.ofDays(TACHO_LOOKBACK_DAYS)), start), start, zone);
            if (s.dataUntil() == null) {
                addOnce(missing, "calc.missing.tacho_data");
                return DriverState.fresh(start, zone);
            }
            if (s.dataUntil().isBefore(start.minus(Duration.ofHours(24)))) {
                warnings.add("calc.warning.tacho_old");
            }
            return s;
        }
        return DriverState.fresh(start, zone);
    }

    private static DriverState manual(Input input, ZoneId zone) {
        CalcDto.ManualState m = input.manual();
        return DriverState.manual(input.start(), zone, nz(m.drivingSinceBreakMin()), nz(m.drivingInShiftMin()),
                m.shiftStart(), nz(m.weekDrivingMin()), nz(m.fortnightDrivingMin()),
                m.extensionsLeft() == null ? 2 : m.extensionsLeft(),
                m.reducedRestsLeft() == null ? 3 : m.reducedRestsLeft(), m.lastWeeklyRestEnd());
    }

    // --- costs ---------------------------------------------------------------------------------------------

    /**
     * Fuel, pay, tolls and other costs. Pay per driver of the crew: km of that driver's own DRIVE items (a crew of
     * two splits the distance), per diem and salary share for the whole trip each; a crew slot without a chosen
     * driver gets no pay lines and no missing code.
     */
    private CalcDto.Costs costs(Input input, Firma firma, int days, Map<Integer, BigDecimal> kmByDriver,
                                boolean payHidden, List<String> missing) {
        CalcDto.Options o = input.options();
        String base = firma.getBaseCurrency();
        BigDecimal km = input.distance();
        List<CalcDto.CostLine> lines = new ArrayList<>();

        BigDecimal consumption = o.consumptionL100km() != null ? o.consumptionL100km()
                : input.vehicle() == null ? null : input.vehicle().getAvgL100km();
        if (consumption == null) {
            missing.add("calc.missing.consumption");
        }
        fuelLines(input, firma, base, km, consumption, lines, missing);

        if (!payHidden) {
            boolean abroad = input.kmByCountry().keySet().stream().anyMatch(c -> !c.equals(firma.getCountry()));
            LocalDate day = LocalDate.ofInstant(input.start(), zone(firma));
            for (int i = 0; i < input.crewSize(); i++) {
                Driver d = i < input.crew().size() ? input.crew().get(i) : null;
                if (d == null) {
                    continue;
                }
                DriverPayRate rate = payRates.findByDriverIdOrderByValidFromDesc(d.getId()).stream()
                        .filter(r -> !r.getValidFrom().isAfter(day)).findFirst().orElse(null);
                if (rate == null) {
                    addOnce(missing, "calc.missing.pay_rate");
                    continue;
                }
                String label = d.getFullName();
                BigDecimal driven = kmByDriver.getOrDefault(i + 1, BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP);
                if (rate.getPerKm() != null) {
                    lines.add(new CalcDto.CostLine("DRIVER_KM", label, null, driven, "KM", rate.getPerKm(),
                            money(driven.multiply(rate.getPerKm())), rate.getCurrency(), null, null));
                }
                BigDecimal perDiem = abroad ? rate.getPerDiemAbroad() : rate.getPerDiemHome();
                if (perDiem != null) {
                    lines.add(new CalcDto.CostLine("DRIVER_PER_DIEM", label, null, BigDecimal.valueOf(days), "DAY",
                            perDiem, money(perDiem.multiply(BigDecimal.valueOf(days))), rate.getCurrency(), null, null));
                }
                if (rate.getMonthlyBase() != null) {
                    BigDecimal daily = rate.getMonthlyBase().divide(THIRTY, 4, RoundingMode.HALF_UP);
                    lines.add(new CalcDto.CostLine("DRIVER_BASE", label, null, BigDecimal.valueOf(days), "DAY",
                            daily.setScale(2, RoundingMode.HALF_UP),
                            money(daily.multiply(BigDecimal.valueOf(days))), rate.getCurrency(), null, null));
                }
            }
        }

        Map<String, List<TollTariff>> byCountry = new TreeMap<>();
        for (TollTariff t : tariffs.findByCountryIn(input.kmByCountry().keySet())) {
            byCountry.computeIfAbsent(t.getCountry(), k -> new ArrayList<>()).add(t);
        }
        LocalDate day = LocalDate.ofInstant(input.start(), zone(firma));
        RouteProvider.Profile truck = routes.truck(input.vehicle());
        Vehicle.EuroClass euro = input.vehicle() == null ? null : input.vehicle().getEuroClass();
        int weightKg = (int) Math.round(truck.weightT() * 1000);
        for (Map.Entry<String, BigDecimal> e : input.tollKmByCountry().entrySet()) {
            if (e.getValue().signum() == 0) {
                continue;
            }
            TollTariff t = tariff(byCountry.getOrDefault(e.getKey(), List.of()), euro, truck.axles(), weightKg, day);
            if (t == null) {
                addOnce(missing, "calc.missing.toll_tariff");
            }
            lines.add(new CalcDto.CostLine("TOLL", null, e.getKey(), e.getValue().setScale(1, RoundingMode.HALF_UP),
                    "KM", t == null ? null : t.getRatePerKm(),
                    t == null ? null : money(e.getValue().multiply(t.getRatePerKm())),
                    t == null ? base : t.getCurrency(), null, null));
        }

        if (o.otherCosts() != null) {
            for (CalcDto.OtherCost c : o.otherCosts()) {
                lines.add(new CalcDto.CostLine("OTHER", InputFields.blankToNull(c.label()), null, null, null, null,
                        money(c.amount()), c.currency() == null ? base : FuelValues.currency(c.currency()), null, null));
            }
        }

        Map<String, BigDecimal> sums = new TreeMap<>();
        for (CalcDto.CostLine l : lines) {
            if (l.amount() != null) {
                sums.merge(l.currency(), l.amount(), BigDecimal::add);
            }
        }
        return new CalcDto.Costs(lines, sums.entrySet().stream()
                .map(e -> new CalcDto.CurrencyTotal(e.getKey(), e.getValue())).toList(), List.copyOf(missing));
    }

    /**
     * FUEL lines: litres = km x consumption / 100 at a net price per litre. The options' price for the whole trip
     * (MANUAL); else per country of the route {@link FuelPrices#resolve} (own fuel cards there, the reference price),
     * then the firma's average in its base currency (OWN_TX_ALL). One line without a country when every country got
     * the same price, else one per country (litres as driven: in proportion to the km).
     */
    private void fuelLines(Input input, Firma firma, String base, BigDecimal km, BigDecimal consumption,
                           List<CalcDto.CostLine> lines, List<String> missing) {
        CalcDto.Options o = input.options();
        LocalDate day = LocalDate.ofInstant(input.start(), zone(firma));
        if (o.fuelPrice() != null) {
            String currency = o.fuelCurrency() != null ? FuelValues.currency(o.fuelCurrency()) : base;
            lines.add(fuelLine(null, km, consumption, new FuelPriceProvider.PriceQuote(null,
                    FuelTransaction.Product.DIESEL, o.fuelPrice(), null, null, currency, null, base, null, day, null,
                    FuelPriceProvider.Quality.MANUAL, null, null), base));
            return;
        }
        Instant since = input.start().minus(Duration.ofDays(FUEL_PRICE_DAYS));
        FuelPriceProvider.PriceQuote average = fuelPrices.firmAverage(FuelTransaction.Product.DIESEL, base, since, day)
                .orElse(null);
        Map<String, FuelPriceProvider.PriceQuote> byCountry = new LinkedHashMap<>();
        for (String country : input.kmByCountry().keySet()) {
            byCountry.put(country, fuelPrices.resolve(country, FuelTransaction.Product.DIESEL, since, day, base)
                    .orElse(average));
        }
        Set<String> distinct = new HashSet<>();
        byCountry.values().forEach(q -> distinct.add(q == null ? "-"
                : q.net().toPlainString() + "|" + q.currency() + "|" + q.quality() + "|" + q.priceDate()));
        if (byCountry.isEmpty() || (distinct.size() == 1 && !distinct.contains("-"))) {
            FuelPriceProvider.PriceQuote q = byCountry.isEmpty() ? average : byCountry.values().iterator().next();
            if (q == null) {
                missing.add("calc.missing.fuel_price");
            }
            lines.add(fuelLine(null, km, consumption, q, base));
            return;
        }
        for (Map.Entry<String, FuelPriceProvider.PriceQuote> e : byCountry.entrySet()) {
            if (e.getValue() == null) {
                addOnce(missing, "calc.missing.fuel_price");
            }
            lines.add(fuelLine(e.getKey(), input.kmByCountry().get(e.getKey()), consumption, e.getValue(), base));
        }
    }

    private static CalcDto.CostLine fuelLine(String country, BigDecimal km, BigDecimal consumption,
                                             FuelPriceProvider.PriceQuote price, String base) {
        BigDecimal litres = consumption == null ? null
                : km.multiply(consumption).divide(HUNDRED, 1, RoundingMode.HALF_UP);
        BigDecimal rate = price == null ? null : price.net();
        return new CalcDto.CostLine("FUEL", null, country, litres, "L", rate,
                litres == null || rate == null ? null : money(litres.multiply(rate)),
                price == null ? base : price.currency(),
                price == null ? FuelPriceProvider.Quality.MISSING.name() : price.quality().name(),
                price == null ? null : price.priceDate());
    }

    /**
     * The matching tariff: country rows valid on the day whose Euro class, axles and weight conditions hold for the
     * truck as it drives ({@link RouteService#truck}: a tractor with its semi-trailer, EU limits for an empty card;
     * a Euro class condition holds only for the class on the card); the most specific one, then the latest valid_from.
     */
    static TollTariff tariff(List<TollTariff> rows, Vehicle.EuroClass euro, int axles, int weightKg, LocalDate day) {
        return rows.stream()
                .filter(t -> t.getValidFrom() == null || !t.getValidFrom().isAfter(day))
                .filter(t -> t.getEuroClass() == null || t.getEuroClass() == euro)
                .filter(t -> t.getAxlesMin() == null || axles >= t.getAxlesMin())
                .filter(t -> t.getWeightMinKg() == null || weightKg >= t.getWeightMinKg())
                .max(Comparator.comparingInt((TollTariff t) -> specificity(t))
                        .thenComparing(t -> t.getValidFrom() == null ? LocalDate.MIN : t.getValidFrom())
                        .thenComparing(TollTariff::getId))
                .orElse(null);
    }

    private static int specificity(TollTariff t) {
        return (t.getEuroClass() == null ? 0 : 1) + (t.getAxlesMin() == null ? 0 : 1)
                + (t.getWeightMinKg() == null ? 0 : 1);
    }

    // --- input ---------------------------------------------------------------------------------------------

    private Input validate(CalcDto.TripInput in, Firma firma) {
        Map<String, String> errors = new LinkedHashMap<>();
        Long firmaId = firma.getId();
        CalcDto.Options o = in.options() == null
                ? new CalcDto.Options(null, null, null, null, null, null, null, null, null) : in.options();
        if (in.start() == null) {
            errors.put("start", ErrorCodes.FIELD_REQUIRED);
        }
        Vehicle vehicle = null;
        if (in.vehicleId() != null) {
            vehicle = vehicles.findByIdAndDeletedFalse(in.vehicleId()).filter(v -> firmaId.equals(v.getFirmaId()))
                    .orElse(null);
            if (vehicle == null) {
                errors.put("vehicleId", ErrorCodes.FIELD_INVALID_VALUE);
            }
        }
        List<Driver> crew = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        if (in.driverIds() != null) {
            for (Long id : in.driverIds()) {
                Driver d = id == null || !seen.add(id) ? null : drivers.findById(id)
                        .filter(x -> firmaId.equals(x.getFirmaId()) && !x.isDeleted()).orElse(null);
                if (d == null) {
                    errors.put("driverIds", ErrorCodes.FIELD_INVALID_VALUE);
                } else {
                    crew.add(d);
                }
            }
        }
        int crewSize = in.crewSize() != null ? in.crewSize() : Math.max(1, crew.size());
        if (crewSize < 1 || crewSize > 2 || crewSize < crew.size()) {
            errors.put("crewSize", ErrorCodes.FIELD_INVALID_VALUE);
        }
        DriverState.Source mode = InputFields.parseEnum(DriverState.Source.class, "driverState", in.driverState(),
                false, errors);
        if (mode == null) {
            mode = crew.isEmpty() ? DriverState.Source.FRESH : DriverState.Source.TACHO;
        }
        if (mode == DriverState.Source.MANUAL && in.manualState() == null) {
            errors.put("manualState", ErrorCodes.FIELD_REQUIRED);
        }
        int speed = o.avgSpeedKmh() == null ? DEFAULT_SPEED_KMH : o.avgSpeedKmh();
        if (speed < 20 || speed > 120) {
            errors.put("options.avgSpeedKmh", ErrorCodes.FIELD_INVALID_VALUE);
            speed = DEFAULT_SPEED_KMH;
        }
        String defaultCountry = o.country() == null ? firma.getCountry()
                : InputFields.country("options.country", o.country(), errors);
        if (o.fuelCurrency() != null && FuelValues.currency(o.fuelCurrency()) == null) {
            errors.put("options.fuelCurrency", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (o.otherCosts() != null) {
            for (int i = 0; i < o.otherCosts().size(); i++) {
                CalcDto.OtherCost c = o.otherCosts().get(i);
                if (c == null || c.amount() == null) {
                    errors.put("options.otherCosts[" + i + "].amount", ErrorCodes.FIELD_REQUIRED);
                } else if (c.currency() != null && FuelValues.currency(c.currency()) == null) {
                    errors.put("options.otherCosts[" + i + "].currency", ErrorCodes.FIELD_INVALID_VALUE);
                }
            }
        }
        List<CalcDto.LegInput> legInputs = in.legs();
        if ((legInputs == null || legInputs.isEmpty()) && in.waypoints() != null && !in.waypoints().isEmpty()) {
            if (!errors.isEmpty()) {
                throw ApiException.validation(errors);
            }
            legInputs = legsOf(in.waypoints(), routes.route("waypoints", in.waypoints(), vehicle, null, false, false));
        }
        List<HosPlanner.Leg> legs = new ArrayList<>();
        Map<String, BigDecimal> kmByCountry = new TreeMap<>();
        Map<String, BigDecimal> tollKmByCountry = new TreeMap<>();
        BigDecimal distance = BigDecimal.ZERO;
        if (legInputs == null || legInputs.isEmpty()) {
            errors.put("legs", ErrorCodes.FIELD_REQUIRED);
        } else {
            for (int i = 0; i < legInputs.size(); i++) {
                CalcDto.LegInput l = legInputs.get(i);
                String at = "legs[" + i + "].";
                if (l == null || l.distanceKm() == null) {
                    errors.put(at + "distanceKm", ErrorCodes.FIELD_REQUIRED);
                    continue;
                }
                if (l.distanceKm().signum() < 0 || l.distanceKm().compareTo(BigDecimal.valueOf(20_000)) > 0) {
                    errors.put(at + "distanceKm", ErrorCodes.FIELD_INVALID_VALUE);
                    continue;
                }
                int duration = l.durationMin() != null ? l.durationMin()
                        : l.distanceKm().multiply(BigDecimal.valueOf(60))
                        .divide(BigDecimal.valueOf(speed), 0, RoundingMode.HALF_UP).intValue();
                if (duration < 0 || duration > 30_000) {
                    errors.put(at + "durationMin", ErrorCodes.FIELD_INVALID_VALUE);
                }
                int service = l.serviceMin() == null ? 0 : l.serviceMin();
                if (service < 0 || service > 24 * 60) {
                    errors.put(at + "serviceMin", ErrorCodes.FIELD_INVALID_VALUE);
                }
                distance = distance.add(l.distanceKm());
                if (l.countries() == null || l.countries().isEmpty()) {
                    if (defaultCountry != null && l.distanceKm().signum() > 0) {
                        kmByCountry.merge(defaultCountry, l.distanceKm(), BigDecimal::add);
                        tollKmByCountry.merge(defaultCountry, l.distanceKm(), BigDecimal::add);
                    }
                } else {
                    BigDecimal sum = BigDecimal.ZERO;
                    for (CalcDto.CountryKm c : l.countries()) {
                        String country = c == null ? null : InputFields.country(at + "countries", c.country(), errors);
                        if (country == null || c.km() == null || c.km().signum() < 0 || (c.tollKm() != null
                                && (c.tollKm().signum() < 0 || c.tollKm().compareTo(c.km()) > 0))) {
                            errors.put(at + "countries", ErrorCodes.FIELD_INVALID_VALUE);
                            continue;
                        }
                        sum = sum.add(c.km());
                        if (c.km().signum() > 0) {
                            kmByCountry.merge(country, c.km(), BigDecimal::add);
                            tollKmByCountry.merge(country, c.tollKm() == null ? c.km() : c.tollKm(),
                                    BigDecimal::add);
                        }
                    }
                    if (sum.subtract(l.distanceKm()).abs().compareTo(BigDecimal.ONE) > 0) {
                        errors.put(at + "countries", ErrorCodes.FIELD_INVALID_VALUE);
                    }
                }
                legs.add(new HosPlanner.Leg(InputFields.blankToNull(l.name()), l.distanceKm().doubleValue(),
                        Math.max(0, duration), Math.max(0, service)));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return new Input(in.start(), vehicle, crew, crewSize, mode, in.manualState(), legs, kmByCountry,
                tollKmByCountry, distance, o);
    }

    /** The route's legs as calculator legs: a waypoint's service time follows the leg that ends there. */
    private static List<CalcDto.LegInput> legsOf(List<CalcDto.PointInput> waypoints, CalcDto.Route route) {
        List<CalcDto.LegInput> legs = new ArrayList<>();
        Integer first = waypoints.getFirst().serviceMin();
        if (first != null && first > 0) {
            legs.add(new CalcDto.LegInput(route.points().getFirst().label(), BigDecimal.ZERO, 0, null, first));
        }
        for (int i = 0; i < route.legs().size(); i++) {
            CalcDto.RouteLeg l = route.legs().get(i);
            List<CalcDto.CountryKm> countries = l.countries().isEmpty() ? null : fit(l.countries(), l.distanceKm());
            legs.add(new CalcDto.LegInput(l.to().label(), l.distanceKm(), l.durationMin(), countries,
                    waypoints.get(i + 1).serviceMin()));
        }
        return legs;
    }

    /** Country km scaled to add up to the leg distance (rounding and unmatched edges). */
    private static List<CalcDto.CountryKm> fit(List<CalcDto.CountryKm> countries, BigDecimal distance) {
        BigDecimal sum = countries.stream().map(CalcDto.CountryKm::km).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() == 0) {
            return null;
        }
        List<CalcDto.CountryKm> out = new ArrayList<>();
        BigDecimal used = BigDecimal.ZERO;
        for (int i = 0; i < countries.size(); i++) {
            CalcDto.CountryKm c = countries.get(i);
            BigDecimal km = (i == countries.size() - 1 ? distance.subtract(used)
                    : c.km().multiply(distance).divide(sum, 1, RoundingMode.HALF_UP)).max(BigDecimal.ZERO);
            used = used.add(km);
            BigDecimal toll = c.tollKm() == null ? null : c.km().signum() == 0 ? BigDecimal.ZERO
                    : c.tollKm().multiply(km).divide(c.km(), 1, RoundingMode.HALF_UP).min(km);
            out.add(new CalcDto.CountryKm(c.country(), km, toll));
        }
        return out;
    }

    // --- helpers -------------------------------------------------------------------------------------------

    static ZoneId zone(Firma firma) {
        try {
            return ZoneId.of(firma.getTimezone());
        } catch (DateTimeException | NullPointerException e) {
            return ZoneOffset.UTC;
        }
    }

    private static BigDecimal km(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static void addOnce(List<String> list, String code) {
        if (!list.contains(code)) {
            list.add(code);
        }
    }
}

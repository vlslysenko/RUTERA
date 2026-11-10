package com.rutera.core.calc;

import com.rutera.common.domain.Vehicle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Trip calculator and toll tariffs (contract of 2026-11-02). */
public final class CalcDto {

    private CalcDto() {
    }

    /** km in a country; tollKm of them on toll roads (null: not known, all km count as toll km). */
    public record CountryKm(String country, BigDecimal km, BigDecimal tollKm) {
    }

    /** A leg of the route: distance, driving time (else distance / avgSpeedKmh), km per country, service at its end. */
    public record LegInput(@Size(max = 200) String name, BigDecimal distanceKm, Integer durationMin,
                           @Size(max = 30) List<CountryKm> countries, Integer serviceMin) {
    }

    /** Counters of {@code driverState: MANUAL}, applied to every driver of the crew; null counts as zero. */
    public record ManualState(Integer drivingSinceBreakMin, Integer drivingInShiftMin, Instant shiftStart,
                              Integer weekDrivingMin, Integer fortnightDrivingMin, Integer extensionsLeft,
                              Integer reducedRestsLeft, Instant lastWeeklyRestEnd) {
    }

    public record OtherCost(@Size(max = 100) String label, @Digits(integer = 10, fraction = 2) BigDecimal amount,
                            String currency) {
    }

    public record Options(Integer avgSpeedKmh, Boolean extendedDriving, Boolean reducedRests,
                          Boolean reducedWeeklyRest,
                          @DecimalMin("0.0") @Digits(integer = 3, fraction = 2) BigDecimal consumptionL100km,
                          @DecimalMin("0.0") @Digits(integer = 4, fraction = 4) BigDecimal fuelPrice,
                          String fuelCurrency, String country, @Valid @Size(max = 20) List<OtherCost> otherCosts) {
    }

    /**
     * Body of {@code POST /app/calc/trip}: the legs, or (when a routing engine is configured) waypoints that are
     * routed into legs; a waypoint's serviceMin is the loading or unloading there.
     */
    public record TripInput(Instant start, Long vehicleId, @Size(max = 2) List<Long> driverIds, Integer crewSize,
                            String driverState, ManualState manualState, @Valid @Size(max = 50) List<LegInput> legs,
                            @Valid @Size(max = 25) List<PointInput> waypoints, @Valid Options options) {
    }

    /** A route point: coordinates, or an address to geocode; serviceMin only for the trip calculator. */
    public record PointInput(Double lat, Double lng, @Size(max = 300) String address, Integer serviceMin) {
    }

    /** Truck dimensions overriding the vehicle's (metres, tonnes). */
    public record ProfileInput(Double heightM, Double widthM, Double lengthM, Double weightT, Double axleLoadT,
                               Integer axles, Boolean hazmat) {
    }

    /** Body of {@code POST /app/calc/route}. */
    /** Body of {@code POST /app/calc/route}; start, drivers and options only feed the cost breakdown. */
    public record RouteInput(@Valid @Size(max = 25) List<PointInput> points, Long vehicleId, @Valid ProfileInput profile,
                             Boolean avoidTolls, Boolean avoidFerries, Instant start,
                             @Size(max = 2) List<Long> driverIds, @Valid Options options) {
    }

    public record RoutePoint(double lat, double lng, String label) {
    }

    public record RouteLeg(RoutePoint from, RoutePoint to, BigDecimal distanceKm, int durationMin,
                           List<CountryKm> countries, String polyline) {
    }

    /** A truck route; polylines are encoded with precision 6; OSM data needs the attribution shown. */
    public record Route(BigDecimal distanceKm, int durationMin, List<RoutePoint> points, List<RouteLeg> legs,
                        int polylinePrecision, String attribution, Truck truck, RouteBreakdown breakdown) {

        Route withBreakdown(RouteBreakdown b) {
            return new Route(distanceKm, durationMin, points, legs, polylinePrecision, attribution, truck, b);
        }
    }

    /**
     * What the route costs, from the trip calculator on its legs: km and toll km per country of the whole route, the
     * arrival with breaks and rests, the days, and the cost lines (FUEL, TOLL per country, driver pay when drivers are
     * given and the user may see pay).
     */
    public record RouteBreakdown(List<CountryKm> countries, Instant eta, int days, Costs costs, boolean payHidden) {
    }

    /** A place for search-as-you-type ({@code GET /app/calc/places}); country is ISO 3166-1 alpha-2. */
    public record Place(String label, double lat, double lng, String country) {
    }

    /** The truck the route was found for: the request's profile, else the vehicle card, else EU limits. */
    public record Truck(double heightM, double widthM, double lengthM, double weightT, Double axleLoadT, int axles,
                        boolean hazmat) {
    }

    public record Totals(BigDecimal distanceKm, int drivingMin, int breakMin, int restMin, int workMin,
                         long durationMin, int dailyRests, int weeklyRests, int days) {
    }

    /** kind: DRIVE, BREAK, DAILY_REST, REDUCED_DAILY_REST, WEEKLY_REST, REDUCED_WEEKLY_REST, SERVICE. */
    public record Event(String kind, Instant start, Instant end, int minutes, BigDecimal kmFrom, BigDecimal kmTo,
                        int legIndex, Integer driver) {
    }

    public record DriverState(Long driverId, String source, Instant dataUntil, int drivingSinceBreakMin,
                              int drivingInShiftMin, Instant shiftStart, int weekDrivingMin,
                              int fortnightDrivingMin, int extensionsLeft, int reducedRestsLeft,
                              Instant weeklyRestDueAt) {
    }

    /** kind: FUEL, DRIVER_KM, DRIVER_PER_DIEM, DRIVER_BASE, TOLL, OTHER; rate and amount null when unknown. */
    /**
     * A cost line. For FUEL: source is the price's quality (FuelPriceProvider.Quality: MANUAL, OWN_TX, REFERENCE,
     * OWN_TX_ALL, MISSING) and priceDate the day of the price; else both are null.
     */
    public record CostLine(String kind, String label, String country, BigDecimal quantity, String unit,
                           BigDecimal rate, BigDecimal amount, String currency, String source, LocalDate priceDate) {
    }

    /** A reference fuel price ({@code fuel_price_ref}): per litre in the country's currency, net without VAT. */
    public record FuelPriceDto(Long id, String country, String product, LocalDate priceDate, BigDecimal net,
                               BigDecimal gross, BigDecimal vatRate, BigDecimal excisePer1000l, String currency,
                               String kind, String source, Instant fetchedAt, Instant updatedAt) {
        static FuelPriceDto of(com.rutera.common.domain.FuelPrice p) {
            return new FuelPriceDto(p.getId(), p.getCountry(), p.getProduct().name(), p.getPriceDate(), p.getNet(),
                    p.getGross(), p.getVatRate(), p.getExcisePer1000l(), p.getCurrency(), p.getKind().name(),
                    p.getSource(), p.getFetchedAt(), p.getUpdatedAt() != null ? p.getUpdatedAt() : p.getCreatedAt());
        }
    }

    /** Body of {@code POST /app/calc/fuel-prices} (operator only): net, or gross with the VAT rate. */
    public record FuelPriceInput(String country, String product, LocalDate priceDate, BigDecimal net,
                                 BigDecimal gross, BigDecimal vatRate, BigDecimal excisePer1000l, String currency,
                                 String kind, @Size(max = 100) String source) {
    }

    public record CurrencyTotal(String currency, BigDecimal amount) {
    }

    public record Costs(List<CostLine> lines, List<CurrencyTotal> totals, List<String> missing) {
    }

    public record Plan(Instant start, Instant eta, Totals totals, List<Event> timeline,
                       List<DriverState> driverStates, Costs costs, boolean payHidden, List<String> warnings) {
    }

    public record TollTariff(Long id, String country, Vehicle.EuroClass euroClass, Integer axlesMin,
                             Integer weightMinKg, BigDecimal ratePerKm, String currency, LocalDate validFrom,
                             String notes, Instant createdAt, Instant updatedAt) {
    }

    /** Body of {@code POST}/{@code PUT /app/calc/toll-tariffs}. */
    public record TollTariffInput(String country, String euroClass, Integer axlesMin, Integer weightMinKg,
                                  @DecimalMin("0.0") @Digits(integer = 5, fraction = 4) BigDecimal ratePerKm,
                                  String currency, LocalDate validFrom, @Size(max = 500) String notes) {
    }
}

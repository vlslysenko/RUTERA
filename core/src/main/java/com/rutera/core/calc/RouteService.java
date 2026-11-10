package com.rutera.core.calc;

import com.rutera.common.domain.Vehicle;
import com.rutera.common.repository.VehicleRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Truck routes for the calculator and the map ({@code POST /app/calc/route}): addresses are geocoded
 * ({@link Geocoder}), the truck's dimensions go to the routing engine ({@link RouteProvider}); the answer has per leg
 * the distance, time, km per country and the shape, and the truck it was found for.
 * <p>
 * The truck: the request's {@code profile}, else the vehicle card (height, gross weight, axles), else the EU limits of
 * Directive 96/53/EC for the kind. A tractor drives as a combination with a semi-trailer: 40 t whatever its card says
 * (the card holds the tractor alone), the axles and height of its default trailer added when known, else 5 axles.
 */
@Service
public class RouteService {

    static final String ATTRIBUTION = "© OpenStreetMap contributors";
    static final int MAX_POINTS = 25;

    /** EU limits for what the request and the card leave open (axle load null: the engine's default). */
    private record Limits(double heightM, double widthM, double lengthM, double weightT, Double axleLoadT, int axles) {
    }

    static final Limits COMBINATION = new Limits(4.0, 2.55, 16.5, 40.0, 11.5, 5);
    static final Limits RIGID = new Limits(4.0, 2.55, 12.0, 18.0, 11.5, 2);
    static final Limits VAN = new Limits(2.7, 2.1, 6.0, 3.5, null, 2);

    private final RouteProvider provider;
    private final Geocoder geocoder;
    private final VehicleRepository vehicles;

    public RouteService(RouteProvider provider, Geocoder geocoder, VehicleRepository vehicles) {
        this.provider = provider;
        this.geocoder = geocoder;
        this.vehicles = vehicles;
    }

    @RequiresPrivilege(Privileges.TRIP_TRIP_VIEW)
    @Transactional(readOnly = true)
    public CalcDto.Route plan(CalcDto.RouteInput in) {
        Map<String, String> errors = new LinkedHashMap<>();
        Vehicle vehicle = null;
        if (in.vehicleId() != null) {
            Long firmaId = InputFields.requireFirmaId();
            vehicle = vehicles.findByIdAndDeletedFalse(in.vehicleId()).filter(v -> firmaId.equals(v.getFirmaId()))
                    .orElse(null);
            if (vehicle == null) {
                errors.put("vehicleId", ErrorCodes.FIELD_INVALID_VALUE);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return route("points", in.points(), vehicle, in.profile(), Boolean.TRUE.equals(in.avoidTolls()),
                Boolean.TRUE.equals(in.avoidFerries()));
    }

    /**
     * The route through the points (field names of errors start with {@code field}); used by {@link #plan} and by
     * the trip calculator, which checked its own privilege.
     */
    CalcDto.Route route(String field, List<CalcDto.PointInput> input, Vehicle vehicle, CalcDto.ProfileInput override,
                        boolean avoidTolls, boolean avoidFerries) {
        if (!provider.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.CALC_ROUTING_DISABLED);
        }
        Map<String, String> errors = new LinkedHashMap<>();
        if (input == null || input.size() < 2 || input.size() > MAX_POINTS) {
            errors.put(field, input == null || input.isEmpty() ? ErrorCodes.FIELD_REQUIRED
                    : ErrorCodes.FIELD_INVALID_VALUE);
            throw ApiException.validation(errors);
        }
        String language = CurrentUser.get().map(u -> u.lang()).orElse("en");
        List<CalcDto.RoutePoint> points = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            CalcDto.PointInput p = input.get(i);
            String at = field + "[" + i + "]";
            if (p != null && p.lat() != null && p.lng() != null) {
                if (Math.abs(p.lat()) > 90 || Math.abs(p.lng()) > 180) {
                    errors.put(at, ErrorCodes.FIELD_INVALID_VALUE);
                } else {
                    points.add(new CalcDto.RoutePoint(p.lat(), p.lng(), InputFields.blankToNull(p.address())));
                }
            } else if (p != null && p.address() != null && !p.address().isBlank()) {
                Optional<Geocoder.Place> place;
                try {
                    place = geocoder.geocode(p.address(), language);
                } catch (Geocoder.Failed e) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.CALC_GEOCODE_FAILED);
                }
                if (place.isEmpty()) {
                    errors.put(at, ErrorCodes.CALC_GEOCODE_NOT_FOUND);
                } else {
                    points.add(new CalcDto.RoutePoint(place.get().lat(), place.get().lng(), place.get().label()));
                }
            } else {
                errors.put(at, ErrorCodes.FIELD_REQUIRED);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        RouteProvider.Profile truck = profile(vehicle, override, avoidTolls, avoidFerries);
        List<RouteProvider.Leg> legs;
        try {
            legs = provider.route(points.stream().map(p -> new RouteProvider.Point(p.lat(), p.lng())).toList(),
                    truck);
        } catch (RouteProvider.NotFound e) {
            throw ApiException.validation(Map.of("route", ErrorCodes.CALC_ROUTE_NOT_FOUND));
        } catch (RouteProvider.Failed e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.CALC_ROUTING_FAILED);
        }
        List<CalcDto.RouteLeg> out = new ArrayList<>();
        double km = 0;
        int minutes = 0;
        for (int i = 0; i < legs.size(); i++) {
            RouteProvider.Leg l = legs.get(i);
            List<CalcDto.CountryKm> countries = l.kmByCountry().entrySet().stream()
                    .map(e -> new CalcDto.CountryKm(e.getKey(), km(e.getValue()))).toList();
            out.add(new CalcDto.RouteLeg(points.get(i), points.get(i + 1), km(l.distanceKm()), l.durationMin(),
                    countries, l.polyline()));
            km += l.distanceKm();
            minutes += l.durationMin();
        }
        return new CalcDto.Route(km(km), minutes, points, out, 6, ATTRIBUTION, new CalcDto.Truck(truck.heightM(),
                truck.widthM(), truck.lengthM(), truck.weightT(), truck.axleLoadT(), truck.axles(), truck.hazmat()));
    }

    /** The truck: the request's profile, else the vehicle card, else the EU limits of its kind (see the class). */
    private RouteProvider.Profile profile(Vehicle v, CalcDto.ProfileInput o, boolean avoidTolls,
                                          boolean avoidFerries) {
        Vehicle.Kind kind = v == null ? Vehicle.Kind.TRACTOR : v.getKind();
        Limits limits = kind == Vehicle.Kind.VAN ? VAN : kind == Vehicle.Kind.TRUCK ? RIGID : COMBINATION;
        Double cardHeight = null;
        Double cardWeight = null;
        Integer cardAxles = null;
        if (v != null && limits == COMBINATION) {
            Vehicle trailer = kind == Vehicle.Kind.TRACTOR && v.getDefaultTrailerId() != null
                    ? vehicles.findByIdAndDeletedFalse(v.getDefaultTrailerId()).orElse(null) : null;
            cardHeight = higher(meters(v.getHeightMm()), trailer == null ? null : meters(trailer.getHeightMm()));
            if (kind == Vehicle.Kind.TRACTOR && trailer != null && v.getAxles() != null && trailer.getAxles() != null) {
                cardAxles = v.getAxles() + trailer.getAxles();
            }
        } else if (v != null) {
            cardHeight = meters(v.getHeightMm());
            cardWeight = v.getGrossWeightKg() == null ? null : v.getGrossWeightKg() / 1000.0;
            cardAxles = v.getAxles();
        }
        double height = first(o == null ? null : o.heightM(), cardHeight, limits.heightM());
        double width = first(o == null ? null : o.widthM(), null, limits.widthM());
        double length = first(o == null ? null : o.lengthM(), null, limits.lengthM());
        double weight = first(o == null ? null : o.weightT(), cardWeight, limits.weightT());
        Double axleLoad = o != null && o.axleLoadT() != null ? o.axleLoadT() : limits.axleLoadT();
        int axles = o != null && o.axles() != null ? o.axles() : cardAxles != null ? cardAxles : limits.axles();
        return new RouteProvider.Profile(height, width, length, weight, axleLoad, axles,
                o != null && Boolean.TRUE.equals(o.hazmat()), avoidTolls, avoidFerries);
    }

    private static Double meters(Integer mm) {
        return mm == null ? null : mm / 1000.0;
    }

    private static Double higher(Double a, Double b) {
        return a == null ? b : b == null ? a : Math.max(a, b);
    }

    private static double first(Double requested, Double card, double limit) {
        return requested != null ? requested : card != null ? card : limit;
    }

    private static BigDecimal km(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }
}

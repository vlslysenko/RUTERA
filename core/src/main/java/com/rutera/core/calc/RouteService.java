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
 * ({@link Geocoder}), the vehicle's height, weight and axles (overridable) go to the routing engine
 * ({@link RouteProvider}); the answer has per leg the distance, time, km per country and the shape.
 */
@Service
public class RouteService {

    static final String ATTRIBUTION = "© OpenStreetMap contributors";
    static final int MAX_POINTS = 25;

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
        List<RouteProvider.Leg> legs;
        try {
            legs = provider.route(points.stream().map(p -> new RouteProvider.Point(p.lat(), p.lng())).toList(),
                    profile(vehicle, override, avoidTolls, avoidFerries));
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
        return new CalcDto.Route(km(km), minutes, points, out, 6, ATTRIBUTION);
    }

    /** The vehicle's height, weight and axles, overridden by the request. */
    private static RouteProvider.Profile profile(Vehicle v, CalcDto.ProfileInput o, boolean avoidTolls,
                                                 boolean avoidFerries) {
        Double height = o != null && o.heightM() != null ? o.heightM()
                : v != null && v.getHeightMm() != null ? v.getHeightMm() / 1000.0 : null;
        Double weight = o != null && o.weightT() != null ? o.weightT()
                : v != null && v.getGrossWeightKg() != null ? v.getGrossWeightKg() / 1000.0 : null;
        Integer axles = o != null && o.axles() != null ? o.axles() : v == null ? null : v.getAxles();
        return new RouteProvider.Profile(height, o == null ? null : o.widthM(), o == null ? null : o.lengthM(),
                weight, o == null ? null : o.axleLoadT(), axles, o != null && Boolean.TRUE.equals(o.hazmat()),
                avoidTolls, avoidFerries);
    }

    private static BigDecimal km(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }
}

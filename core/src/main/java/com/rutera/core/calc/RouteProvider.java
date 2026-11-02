package com.rutera.core.calc;

import java.util.List;
import java.util.Map;

/**
 * A routing engine for trucks ({@link ValhallaRouteProvider} when {@code rutera.routing.url} is set, else
 * {@link ManualRouteProvider}, which routes nothing and the calculator takes the legs from the request).
 */
public interface RouteProvider {

    record Point(double lat, double lng) {
    }

    /** Vehicle dimensions in metres and tonnes (null = the engine's default) and what to avoid. */
    record Profile(Double heightM, Double widthM, Double lengthM, Double weightT, Double axleLoadT, Integer axles,
                   boolean hazmat, boolean avoidTolls, boolean avoidFerries) {
    }

    /** One leg between two consecutive points; polyline with precision 6; km per ISO country code. */
    record Leg(double distanceKm, int durationMin, String polyline, Map<String, Double> kmByCountry) {
    }

    /** No route for this vehicle between the points (answers 422). */
    final class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** The engine is unreachable or answered nonsense (answers 502). */
    final class Failed extends RuntimeException {
        public Failed(String message) {
            super(message);
        }
    }

    boolean enabled();

    /** The legs between the points (at least two), in order. */
    List<Leg> route(List<Point> points, Profile profile);
}

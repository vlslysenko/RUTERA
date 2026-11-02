package com.rutera.core.calc;

import com.rutera.common.domain.Vehicle;

import java.util.List;
import java.util.Optional;

/**
 * Routing for the trip calculator (a Valhalla or GraphHopper server comes later). Until one is configured the
 * calculator takes the legs from the request; {@link #route} answers empty.
 */
public interface RouteProvider {

    /** A point of the route: an address or coordinates. */
    record Waypoint(String address, Double lat, Double lng) {
    }

    /** The legs between the waypoints for this vehicle (height, weight, axles), or empty when it cannot route. */
    Optional<List<CalcDto.LegInput>> route(List<Waypoint> waypoints, Vehicle vehicle);
}

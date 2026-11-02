package com.rutera.core.calc;

import com.rutera.common.domain.Vehicle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** The {@link RouteProvider} until a routing server is configured: no routing, the request brings its legs. */
@Component
public class ManualRouteProvider implements RouteProvider {

    @Override
    public Optional<List<CalcDto.LegInput>> route(List<Waypoint> waypoints, Vehicle vehicle) {
        return Optional.empty();
    }
}

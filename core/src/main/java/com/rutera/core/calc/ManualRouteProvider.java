package com.rutera.core.calc;

import java.util.List;

/** The {@link RouteProvider} while no routing engine is configured: routes nothing. */
public class ManualRouteProvider implements RouteProvider {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public List<Leg> route(List<Point> points, Profile profile) {
        throw new Failed("routing is not configured");
    }
}

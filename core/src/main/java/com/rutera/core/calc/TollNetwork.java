package com.rutera.core.calc;

import java.util.Map;
import java.util.Set;

/**
 * Which roads of a route charge a truck toll per km. The OSM {@code toll=yes} flag (Valhalla's {@code edge.toll})
 * marks concession motorways and bridges, but the electronic truck tolls of most countries charge a whole road
 * network that OSM does not flag: there the road classes of that network count as tolled too.
 * <p>
 * Networks as of 2026 (by Valhalla road class): DE motorways and federal roads (motorway, trunk, primary); HU, DK
 * and BG motorways and main roads (motorway, trunk, primary); PL (e-TOLL A/S roads), AT, CZ, SK, BE, NL, SI
 * motorways and expressways (motorway, trunk); CH every road (LSVA). Everywhere else only the flagged roads (the
 * concession motorways of FR, IT, ES, PT, HR, GR, ...); countries with time-based vignettes have no km toll here.
 * Approximations: parts of the networks on national roads (PL GP/G, CZ and SK first-class roads, BE regional roads)
 * are not in these classes, and a flagged concession section is charged at the country's tariff.
 */
final class TollNetwork {

    private static final Set<String> MOTORWAYS = Set.of("motorway", "trunk");
    private static final Set<String> MAIN_ROADS = Set.of("motorway", "trunk", "primary");
    private static final Map<String, Set<String>> NETWORKS = Map.ofEntries(
            Map.entry("DE", MAIN_ROADS), Map.entry("HU", MAIN_ROADS), Map.entry("DK", MAIN_ROADS),
            Map.entry("BG", MAIN_ROADS),
            Map.entry("PL", MOTORWAYS), Map.entry("AT", MOTORWAYS), Map.entry("CZ", MOTORWAYS),
            Map.entry("SK", MOTORWAYS), Map.entry("BE", MOTORWAYS), Map.entry("NL", MOTORWAYS),
            Map.entry("SI", MOTORWAYS));
    private static final Set<String> ALL_ROADS = Set.of("CH", "LI");

    private TollNetwork() {
    }

    /** True when a truck pays a km toll on this road: flagged as toll, or part of the country's toll network. */
    static boolean tolled(String country, String roadClass, boolean flagged) {
        if (flagged || ALL_ROADS.contains(country)) {
            return true;
        }
        Set<String> network = NETWORKS.get(country);
        return network != null && roadClass != null && network.contains(roadClass);
    }
}

package com.rutera.tacho.model;

import com.rutera.tacho.io.ByteReader;

import java.time.Instant;

/**
 * GNSSPlaceRecord (11 bytes) or GNSSPlaceAuthRecord (12 bytes, with the authentication status of version 2).
 * Coordinates are decimal degrees; null when the position was not available.
 *
 * @param accuracy GNSSAccuracy in metres (1..100)
 * @param authenticationStatus PositionAuthenticationStatus (0 not authenticated, 1 authenticated), null for GEN2
 */
public record GnssPlace(Instant timestamp, int accuracy, Double latitude, Double longitude,
                        Integer authenticationStatus) {

    public static GnssPlace read(ByteReader r) {
        return read(r, false);
    }

    public static GnssPlace read(ByteReader r, boolean withAuthStatus) {
        Instant ts = r.timeReal();
        int accuracy = r.u8();
        int lat = r.s24();
        int lon = r.s24();
        Integer auth = withAuthStatus ? r.u8() : null;
        if (ts == null && lat == 0 && lon == 0) {
            return new GnssPlace(null, accuracy, null, null, auth);
        }
        return new GnssPlace(ts, accuracy, toDegrees(lat, 90), toDegrees(lon, 180), auth);
    }

    public boolean hasPosition() {
        return latitude != null && longitude != null;
    }

    /**
     * GeoCoordinates are ±DDMM.M times 10 (±DDDMM.M for longitude): 52134 is 52°13.4'. Values beyond the range
     * (the spare top value of the range, 7FFFFFh) mean the position is unknown.
     */
    static Double toDegrees(int value, int maxDegrees) {
        int abs = Math.abs(value);
        if (abs > maxDegrees * 1000 || abs % 1000 >= 600) {
            return null;
        }
        double degrees = abs / 1000 + (abs % 1000) / 10.0 / 60.0;
        double rounded = Math.round(degrees * 1_000_000d) / 1_000_000d;
        return value < 0 ? -rounded : rounded;
    }
}

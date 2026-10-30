package com.rutera.tacho.model;

import com.rutera.tacho.io.ByteReader;

import java.time.Instant;

/**
 * PlaceRecord: where a daily work period began or ended (10 bytes in GEN1; GEN2 adds the GNSS position).
 *
 * @param entryType EntryTypeDailyWorkPeriod: 0 begin, 1 end, 2 begin entered manually, 3 end entered manually,
 *                  4 begin assumed by the VU, 5 end assumed by the VU
 * @param region RegionNumeric (only Spain has regions), 0 if none
 * @param gnss position at that moment (GEN2), otherwise null
 */
public record PlaceRecord(Instant entryTime, int entryType, Nation country, int region, Integer odometerKm,
                          GnssPlace gnss) {

    public static final int SIZE_GEN1 = 10;
    public static final int SIZE_GEN2 = 21;
    public static final int SIZE_GEN2V2 = 22;

    /** Reads a record of the given size (10, 21 or 22 bytes). */
    public static PlaceRecord read(ByteReader r, int size) {
        Instant time = r.timeReal();
        int type = r.u8();
        Nation country = Nation.of(r.u8());
        int region = r.u8();
        int odometer = r.u24();
        GnssPlace gnss = null;
        if (size >= SIZE_GEN2V2) {
            gnss = GnssPlace.read(r, true);
        } else if (size >= SIZE_GEN2) {
            gnss = GnssPlace.read(r, false);
        }
        return new PlaceRecord(time, type, country, region, odometer, gnss);
    }

    public boolean isBegin() {
        return entryType % 2 == 0;
    }

    public boolean isManual() {
        return entryType == 2 || entryType == 3;
    }
}

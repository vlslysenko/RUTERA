package com.rutera.tacho.model;

import com.rutera.tacho.io.ByteReader;

/** VehicleRegistrationIdentification: registering member state and plate (code page + 13 bytes). */
public record VehicleRegistration(Nation nation, String number) {

    /** 15 bytes; null when no plate is recorded. */
    public static VehicleRegistration read(ByteReader r) {
        Nation nation = Nation.of(r.u8());
        String number = r.text(14);
        return number == null ? null : new VehicleRegistration(nation, number);
    }

    /** VehicleRegistrationNumber without the nation (14 bytes, second generation VU overview version 1). */
    public static VehicleRegistration readNumberOnly(ByteReader r) {
        String number = r.text(14);
        return number == null ? null : new VehicleRegistration(Nation.of(0), number);
    }
}

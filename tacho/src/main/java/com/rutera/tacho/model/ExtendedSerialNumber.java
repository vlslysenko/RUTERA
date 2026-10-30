package com.rutera.tacho.model;

import com.rutera.tacho.io.ByteReader;

/**
 * ExtendedSerialNumber (8 bytes) of a card, VU or sensor.
 *
 * @param monthYear manufacturing month and year as "MM/YY", null if not BCD
 * @param type EquipmentType
 */
public record ExtendedSerialNumber(long serialNumber, String monthYear, int type, int manufacturerCode) {

    public static ExtendedSerialNumber read(ByteReader r) {
        long serial = r.u32();
        int mmyy = r.bcd(2);
        int type = r.u8();
        int manufacturer = r.u8();
        String monthYear = mmyy < 0 ? null : String.format("%02d/%02d", mmyy / 100, mmyy % 100);
        return new ExtendedSerialNumber(serial, monthYear, type, manufacturer);
    }
}

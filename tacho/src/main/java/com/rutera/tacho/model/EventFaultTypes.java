package com.rutera.tacho.model;

import java.util.HashMap;
import java.util.Map;

/**
 * EventFaultType codes of the data dictionary as stable keys (stored as {@code tacho_event.code} and translated by
 * the client). Codes 80h..FFh are manufacturer specific.
 */
public final class EventFaultTypes {

    private static final Map<Integer, String> KEYS = new HashMap<>();

    static {
        put(0x00, "EVENT_NO_DETAILS");
        put(0x01, "INVALID_CARD_INSERTION");
        put(0x02, "CARD_CONFLICT");
        put(0x03, "TIME_OVERLAP");
        put(0x04, "DRIVING_WITHOUT_CARD");
        put(0x05, "CARD_INSERTION_WHILE_DRIVING");
        put(0x06, "LAST_CARD_SESSION_NOT_CLOSED");
        put(0x07, "OVER_SPEEDING");
        put(0x08, "POWER_SUPPLY_INTERRUPTION");
        put(0x09, "MOTION_DATA_ERROR");
        put(0x0A, "VEHICLE_MOTION_CONFLICT");
        put(0x0B, "TIME_CONFLICT");
        put(0x0C, "REMOTE_COMMUNICATION_ERROR");
        put(0x0D, "GNSS_POSITION_ABSENT");
        put(0x0E, "EXTERNAL_GNSS_COMMUNICATION_ERROR");
        put(0x10, "VU_SECURITY_BREACH");
        put(0x11, "MOTION_SENSOR_AUTHENTICATION_FAILURE");
        put(0x12, "CARD_AUTHENTICATION_FAILURE");
        put(0x13, "UNAUTHORISED_MOTION_SENSOR_CHANGE");
        put(0x14, "CARD_DATA_INTEGRITY_ERROR");
        put(0x15, "STORED_DATA_INTEGRITY_ERROR");
        put(0x16, "INTERNAL_DATA_TRANSFER_ERROR");
        put(0x17, "UNAUTHORISED_CASE_OPENING");
        put(0x18, "HARDWARE_SABOTAGE");
        put(0x19, "GNSS_TAMPER_DETECTION");
        put(0x1A, "EXTERNAL_GNSS_AUTHENTICATION_FAILURE");
        put(0x1B, "EXTERNAL_GNSS_CERTIFICATE_EXPIRED");
        put(0x20, "SENSOR_SECURITY_BREACH");
        put(0x21, "SENSOR_AUTHENTICATION_FAILURE");
        put(0x22, "SENSOR_STORED_DATA_INTEGRITY_ERROR");
        put(0x23, "SENSOR_INTERNAL_DATA_TRANSFER_ERROR");
        put(0x24, "SENSOR_UNAUTHORISED_CASE_OPENING");
        put(0x25, "SENSOR_HARDWARE_SABOTAGE");
        put(0x30, "EQUIPMENT_FAULT");
        put(0x31, "VU_INTERNAL_FAULT");
        put(0x32, "PRINTER_FAULT");
        put(0x33, "DISPLAY_FAULT");
        put(0x34, "DOWNLOADING_FAULT");
        put(0x35, "SENSOR_FAULT");
        put(0x36, "INTERNAL_GNSS_FAULT");
        put(0x37, "EXTERNAL_GNSS_FAULT");
        put(0x38, "REMOTE_COMMUNICATION_FAULT");
        put(0x39, "ITS_INTERFACE_FAULT");
        put(0x40, "CARD_FAULT");
    }

    private EventFaultTypes() {
    }

    private static void put(int code, String key) {
        KEYS.put(code, key);
    }

    public static String key(int code) {
        String key = KEYS.get(code);
        if (key != null) {
            return key;
        }
        return code >= 0x80 ? String.format("MANUFACTURER_%02X", code) : String.format("UNKNOWN_%02X", code);
    }

    /** Faults are codes 30h..4Fh; everything else is an event. */
    public static boolean isFault(int code) {
        return code >= 0x30 && code <= 0x4F;
    }
}

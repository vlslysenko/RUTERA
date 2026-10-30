package com.rutera.tacho.vu;

import com.rutera.tacho.TachoFormatException;
import com.rutera.tacho.TachoFormatException.Reason;
import com.rutera.tacho.io.ByteReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Second generation VU data: every element is a record array, header recordType (1 byte), recordSize (2),
 * noOfRecords (2), then the records. Each record is handed out as a reader of exactly {@code size} bytes, so a
 * longer record of a later version does not break the stream.
 */
record RecordArray(int type, int size, int count, int offset, ByteReader body) {

    static final int ACTIVITY_CHANGE_INFO = 0x01;
    static final int CARD_SLOTS_STATUS = 0x02;
    static final int CURRENT_DATE_TIME = 0x03;
    static final int MEMBER_STATE_CERTIFICATE = 0x04;
    static final int ODOMETER_VALUE_MIDNIGHT = 0x05;
    static final int DATE_OF_DAY_DOWNLOADED = 0x06;
    static final int SENSOR_PAIRED = 0x07;
    static final int SIGNATURE = 0x08;
    static final int SPECIFIC_CONDITION_RECORD = 0x09;
    static final int VEHICLE_IDENTIFICATION_NUMBER = 0x0A;
    static final int VEHICLE_REGISTRATION_NUMBER = 0x0B;
    static final int VU_CALIBRATION_RECORD = 0x0C;
    static final int VU_CARD_IW_RECORD = 0x0D;
    static final int VU_CARD_RECORD = 0x0E;
    static final int VU_CERTIFICATE = 0x0F;
    static final int VU_COMPANY_LOCKS_RECORD = 0x10;
    static final int VU_CONTROL_ACTIVITY_RECORD = 0x11;
    static final int VU_DETAILED_SPEED_BLOCK = 0x12;
    static final int VU_DOWNLOADABLE_PERIOD = 0x13;
    static final int VU_DOWNLOAD_ACTIVITY_DATA = 0x14;
    static final int VU_EVENT_RECORD = 0x15;
    static final int VU_GNSS_AD_RECORD = 0x16;
    static final int VU_ITS_CONSENT_RECORD = 0x17;
    static final int VU_FAULT_RECORD = 0x18;
    static final int VU_IDENTIFICATION = 0x19;
    static final int VU_OVER_SPEEDING_CONTROL_DATA = 0x1A;
    static final int VU_OVER_SPEEDING_EVENT_RECORD = 0x1B;
    static final int VU_PLACE_DAILY_WORK_PERIOD_RECORD = 0x1C;
    static final int VU_TIME_ADJUSTMENT_GNSS_RECORD = 0x1D;
    static final int VU_TIME_ADJUSTMENT_RECORD = 0x1E;
    static final int VU_POWER_SUPPLY_INTERRUPTION_RECORD = 0x1F;
    static final int SENSOR_PAIRED_RECORD = 0x20;
    static final int SENSOR_EXTERNAL_GNSS_COUPLED_RECORD = 0x21;
    static final int VU_BORDER_CROSSING_RECORD = 0x22;
    static final int VU_LOAD_UNLOAD_RECORD = 0x23;
    static final int VEHICLE_REGISTRATION_IDENTIFICATION = 0x24;

    /** Reads record arrays up to and including the signature array that ends a transfer. */
    static List<RecordArray> readTransfer(ByteReader r) {
        List<RecordArray> arrays = new ArrayList<>();
        while (true) {
            int offset = r.absolutePosition();
            int type = r.u8();
            int size = r.u16();
            int count = r.u16();
            long total = (long) size * count;
            if (total > r.remaining()) {
                throw new TachoFormatException(Reason.TRUNCATED, String.format(
                        "Record array %02Xh: %d x %d bytes, %d left", type, count, size, r.remaining()), offset);
            }
            arrays.add(new RecordArray(type, size, count, offset, r.slice((int) total)));
            if (type == SIGNATURE) {
                return arrays;
            }
        }
    }

    List<ByteReader> records() {
        List<ByteReader> list = new ArrayList<>(count);
        ByteReader r = body.duplicate();
        for (int i = 0; i < count; i++) {
            list.add(r.slice(size));
        }
        return list;
    }

    ByteReader first() {
        if (count == 0) {
            return null;
        }
        return records().getFirst();
    }
}

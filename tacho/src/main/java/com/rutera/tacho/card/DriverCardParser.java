package com.rutera.tacho.card;

import com.rutera.tacho.Generation;
import com.rutera.tacho.TachoFormatException;
import com.rutera.tacho.TachoFormatException.Reason;
import com.rutera.tacho.io.ByteReader;
import com.rutera.tacho.model.ActivityChange;
import com.rutera.tacho.model.CardNumber;
import com.rutera.tacho.model.ExtendedSerialNumber;
import com.rutera.tacho.model.GnssPlace;
import com.rutera.tacho.model.Nation;
import com.rutera.tacho.model.PlaceRecord;
import com.rutera.tacho.model.SpecificCondition;
import com.rutera.tacho.model.VehicleRegistration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Driver card download (Appendix 7, section 3): a concatenation of TLV objects, tag = FID (2 bytes) + appendix
 * (1 byte), length (2 bytes), value. Appendix 00h/01h are the data and signature of an EF of DF Tachograph (GEN1),
 * 02h/03h of DF Tachograph_G2. EF ICC and EF IC (master file) come with appendix 00h and, on second generation
 * cards, again with 02h. Every EF is parsed on its own: a damaged EF gives a warning and the rest is still read.
 */
public final class DriverCardParser {

    static final int EF_ICC = 0x0002;
    static final int EF_IC = 0x0005;
    static final int EF_APPLICATION_IDENTIFICATION = 0x0501;
    static final int EF_EVENTS_DATA = 0x0502;
    static final int EF_FAULTS_DATA = 0x0503;
    static final int EF_DRIVER_ACTIVITY_DATA = 0x0504;
    static final int EF_VEHICLES_USED = 0x0505;
    static final int EF_PLACES = 0x0506;
    static final int EF_CURRENT_USAGE = 0x0507;
    static final int EF_CONTROL_ACTIVITY_DATA = 0x0508;
    static final int EF_CARD_DOWNLOAD = 0x050E;
    static final int EF_IDENTIFICATION = 0x0520;
    static final int EF_DRIVING_LICENCE_INFO = 0x0521;
    static final int EF_SPECIFIC_CONDITIONS = 0x0522;
    static final int EF_VEHICLE_UNITS_USED = 0x0523;
    static final int EF_GNSS_PLACES = 0x0524;
    static final int EF_APPLICATION_IDENTIFICATION_V2 = 0x0525;
    static final int EF_PLACES_AUTHENTICATION = 0x0526;
    static final int EF_GNSS_PLACES_AUTHENTICATION = 0x0527;
    static final int EF_BORDER_CROSSINGS = 0x0528;
    static final int EF_LOAD_UNLOAD_OPERATIONS = 0x0529;
    static final int EF_LOAD_TYPE_ENTRIES = 0x0530;
    static final int EF_VU_CONFIGURATION = 0x0540;
    static final int EF_CARD_CERTIFICATE = 0xC100;
    static final int EF_CARD_SIGN_CERTIFICATE = 0xC101;
    static final int EF_CA_CERTIFICATE = 0xC108;
    static final int EF_LINK_CERTIFICATE = 0xC109;

    private static final Set<Integer> KNOWN_FIDS = Set.of(EF_ICC, EF_IC, EF_APPLICATION_IDENTIFICATION,
            EF_EVENTS_DATA, EF_FAULTS_DATA, EF_DRIVER_ACTIVITY_DATA, EF_VEHICLES_USED, EF_PLACES, EF_CURRENT_USAGE,
            EF_CONTROL_ACTIVITY_DATA, EF_CARD_DOWNLOAD, EF_IDENTIFICATION, EF_DRIVING_LICENCE_INFO,
            EF_SPECIFIC_CONDITIONS, EF_VEHICLE_UNITS_USED, EF_GNSS_PLACES, EF_APPLICATION_IDENTIFICATION_V2,
            EF_PLACES_AUTHENTICATION, EF_GNSS_PLACES_AUTHENTICATION, EF_BORDER_CROSSINGS,
            EF_LOAD_UNLOAD_OPERATIONS, EF_LOAD_TYPE_ENTRIES, EF_VU_CONFIGURATION, EF_CARD_CERTIFICATE,
            EF_CARD_SIGN_CERTIFICATE, EF_CA_CERTIFICATE, EF_LINK_CERTIFICATE);

    private static final int EVENT_RECORD_SIZE = 24;
    private static final int VEHICLE_RECORD_SIZE_GEN1 = 31;
    private static final int VEHICLE_RECORD_SIZE_GEN2 = 48;
    private static final int VEHICLE_UNIT_RECORD_SIZE = 10;
    private static final int GNSS_AD_RECORD_SIZE = 18;
    private static final int BORDER_CROSSING_RECORD_SIZE = 17;
    private static final int LOAD_UNLOAD_RECORD_SIZE = 20;
    private static final int LOAD_TYPE_ENTRY_RECORD_SIZE = 5;

    private record Tlv(int fid, int appendix, int offset, byte[] value) {
    }

    private DriverCardParser() {
    }

    /**
     * Download tools write EF ICC first (tag 0002h, appendix 00h); otherwise a TLV walk must end exactly at the end
     * of the file and contain EF ICC or EF Identification. A truncated card download is still recognised, so that
     * {@link #parse} can report it as TRUNCATED.
     */
    public static boolean looksLikeCard(byte[] data) {
        if (data.length >= 5 && data[0] == 0 && data[1] == 0x02 && data[2] == 0) {
            return true;
        }
        try {
            List<Tlv> tlvs = readTlvs(data, null);
            return !tlvs.isEmpty() && KNOWN_FIDS.contains(tlvs.getFirst().fid())
                    && tlvs.stream().anyMatch(t -> t.fid() == EF_IDENTIFICATION || t.fid() == EF_ICC);
        } catch (TachoFormatException e) {
            return false;
        }
    }

    public static DriverCardFile parse(byte[] data) {
        List<String> warnings = new ArrayList<>();
        List<Tlv> tlvs = readTlvs(data, warnings);
        Map<Integer, byte[]> gen1 = new LinkedHashMap<>();
        Map<Integer, byte[]> gen2 = new LinkedHashMap<>();
        CardIcc icc = null;
        for (Tlv t : tlvs) {
            switch (t.appendix()) {
                case 0x00 -> gen1.putIfAbsent(t.fid(), t.value());
                case 0x02 -> gen2.putIfAbsent(t.fid(), t.value());
                default -> {
                    // 01h/03h: signatures, verified later
                }
            }
            if (t.fid() == EF_ICC && icc == null && (t.appendix() == 0x00 || t.appendix() == 0x02)) {
                icc = guard(warnings, "EF ICC", () -> parseIcc(t.value()));
            }
        }
        if (!gen1.containsKey(EF_IDENTIFICATION) && !gen2.containsKey(EF_IDENTIFICATION)) {
            throw new TachoFormatException(Reason.NOT_A_TACHOGRAPH_FILE, "Card download without EF Identification");
        }

        CardApplication app1 = gen1.containsKey(EF_IDENTIFICATION) || gen1.containsKey(EF_DRIVER_ACTIVITY_DATA)
                ? parseApplication(gen1, Generation.GEN1, warnings) : null;
        CardApplication app2 = null;
        if (gen2.containsKey(EF_IDENTIFICATION) || gen2.containsKey(EF_DRIVER_ACTIVITY_DATA)) {
            boolean v2 = gen2.containsKey(EF_APPLICATION_IDENTIFICATION_V2)
                    || gen2.containsKey(EF_BORDER_CROSSINGS) || gen2.containsKey(EF_LOAD_UNLOAD_OPERATIONS);
            app2 = parseApplication(gen2, v2 ? Generation.GEN2V2 : Generation.GEN2, warnings);
        }

        CardApplication primary = app2 != null ? app2 : app1;
        CardApplicationIdentification appId = primary.applicationIdentification();
        if (appId != null && appId.cardType() != CardNumber.DRIVER_CARD) {
            throw new TachoFormatException(Reason.UNSUPPORTED,
                    "Only driver cards are supported, this is a card of type " + appId.cardType());
        }
        Generation generation = app2 != null ? app2.generation() : Generation.GEN1;
        return new DriverCardFile(generation, icc, app1, app2, List.copyOf(warnings));
    }

    private static List<Tlv> readTlvs(byte[] data, List<String> warnings) {
        List<Tlv> result = new ArrayList<>();
        ByteReader r = new ByteReader(data);
        while (r.remaining() >= 5) {
            int offset = r.absolutePosition();
            int fid = r.u16();
            int appendix = r.u8();
            int length = r.u16();
            if (appendix > 0x03 || length == 0xFFFF) {
                throw new TachoFormatException(Reason.CORRUPT, "Bad TLV tag " + hex4(fid) + "/" + appendix, offset);
            }
            result.add(new Tlv(fid, appendix, offset, r.bytes(length)));
        }
        if (r.hasRemaining()) {
            if (warnings == null) {
                throw new TachoFormatException(Reason.TRUNCATED, "Trailing bytes after the last TLV",
                        r.absolutePosition());
            }
            warnings.add(r.remaining() + " trailing bytes after the last EF ignored");
        }
        if (result.isEmpty()) {
            throw new TachoFormatException(Reason.NOT_A_TACHOGRAPH_FILE, "No TLV objects");
        }
        return result;
    }

    private static CardApplication parseApplication(Map<Integer, byte[]> ef, Generation gen,
                                                    List<String> warnings) {
        String df = gen == Generation.GEN1 ? "GEN1" : "GEN2";
        boolean g2 = gen != Generation.GEN1;
        Ctx c = new Ctx(df, warnings);

        CardApplicationIdentification appId = c.parse(ef, EF_APPLICATION_IDENTIFICATION,
                DriverCardParser::parseApplicationIdentification);
        if (appId != null && ef.containsKey(EF_APPLICATION_IDENTIFICATION_V2)) {
            CardApplicationIdentification base = appId;
            appId = c.parse(ef, EF_APPLICATION_IDENTIFICATION_V2, v -> {
                ByteReader r = new ByteReader(v);
                r.u16(); // lengthOfFollowingData
                return base.withV2(r.u16(), r.u16(), r.u16());
            });
            if (appId == null) {
                appId = base;
            }
        }
        CardApplicationIdentification id = appId;

        CardIdentification[] identification = new CardIdentification[1];
        DriverCardHolder holder = c.parse(ef, EF_IDENTIFICATION, v -> {
            ByteReader r = new ByteReader(v);
            identification[0] = new CardIdentification(Nation.of(r.u8()), r.ia5(16), r.name(), r.timeReal(),
                    r.timeReal(), r.timeReal());
            if (!r.hasRemaining()) {
                return null;
            }
            return new DriverCardHolder(r.name(), r.name(), r.datef(), r.ia5(2));
        });

        DrivingLicence licence = c.parse(ef, EF_DRIVING_LICENCE_INFO, v -> {
            ByteReader r = new ByteReader(v);
            return new DrivingLicence(r.name(), Nation.of(r.u8()), r.ia5(16));
        });
        Instant lastDownload = c.parse(ef, EF_CARD_DOWNLOAD, v -> new ByteReader(v).timeReal());

        List<CardEventFault> events = c.list(ef, EF_EVENTS_DATA, v -> fixedRecords(v, 0, EVENT_RECORD_SIZE,
                DriverCardParser::eventFault, c, "EF Events_Data"));
        List<CardEventFault> faults = c.list(ef, EF_FAULTS_DATA, v -> fixedRecords(v, 0, EVENT_RECORD_SIZE,
                DriverCardParser::eventFault, c, "EF Faults_Data"));
        events.sort(Comparator.comparing(CardEventFault::begin));
        faults.sort(Comparator.comparing(CardEventFault::begin));

        List<CardActivityDay> days = c.list(ef, EF_DRIVER_ACTIVITY_DATA, v -> activity(v, c));

        int vehicleSize = g2 ? VEHICLE_RECORD_SIZE_GEN2 : VEHICLE_RECORD_SIZE_GEN1;
        List<CardVehicleUse> vehicles = c.list(ef, EF_VEHICLES_USED, v -> fixedRecords(v, 2, vehicleSize,
                r -> vehicleUse(r, g2), c, "EF Vehicles_Used"));
        vehicles.sort(Comparator.comparing(CardVehicleUse::firstUse));

        List<PlaceRecord> places = c.list(ef, EF_PLACES, v -> {
            int pointer = g2 ? 2 : 1;
            int size = PlaceRecord.SIZE_GEN1;
            if (g2) {
                size = recordSize(v.length - pointer, id == null ? 0 : id.placeRecords(),
                        PlaceRecord.SIZE_GEN2, PlaceRecord.SIZE_GEN2, PlaceRecord.SIZE_GEN2V2);
            }
            int recordSize = size;
            return fixedRecords(v, pointer, recordSize, r -> {
                PlaceRecord p = PlaceRecord.read(r, recordSize);
                return p.entryTime() == null ? null : p;
            }, c, "EF Places");
        });
        places.sort(Comparator.comparing(PlaceRecord::entryTime));

        CardCurrentUse currentUse = c.parse(ef, EF_CURRENT_USAGE, v -> {
            ByteReader r = new ByteReader(v);
            Instant open = r.timeReal();
            VehicleRegistration vehicle = VehicleRegistration.read(r);
            return open == null ? null : new CardCurrentUse(open, vehicle);
        });
        CardControlActivity control = c.parse(ef, EF_CONTROL_ACTIVITY_DATA, v -> {
            ByteReader r = new ByteReader(v);
            int type = r.u8();
            Instant time = r.timeReal();
            CardNumber card = CardNumber.read(r);
            VehicleRegistration vehicle = VehicleRegistration.read(r);
            Instant begin = r.timeReal();
            Instant end = r.timeReal();
            return time == null ? null : new CardControlActivity(type, time, card, vehicle, begin, end);
        });

        List<SpecificCondition> conditions = c.list(ef, EF_SPECIFIC_CONDITIONS, v -> {
            // GEN1: no pointer (56 x 5 bytes); GEN2: conditionPointerNewestRecord + records
            int pointer = v.length % SpecificCondition.SIZE == 2 ? 2 : 0;
            return fixedRecords(v, pointer, SpecificCondition.SIZE, r -> {
                SpecificCondition s = SpecificCondition.read(r);
                return s.time() == null ? null : s;
            }, c, "EF Specific_Conditions");
        });
        conditions.sort(Comparator.comparing(SpecificCondition::time));

        List<CardVehicleUnitUse> vehicleUnits = new ArrayList<>();
        List<CardGnssAccumulatedDriving> gnss = new ArrayList<>();
        List<CardBorderCrossing> borders = new ArrayList<>();
        List<CardLoadUnload> loads = new ArrayList<>();
        List<CardLoadTypeEntry> loadTypes = new ArrayList<>();
        if (g2) {
            vehicleUnits = c.list(ef, EF_VEHICLE_UNITS_USED, v -> fixedRecords(v, 2, VEHICLE_UNIT_RECORD_SIZE, r -> {
                Instant ts = r.timeReal();
                int manufacturer = r.u8();
                int device = r.u8();
                String sw = r.ia5(4);
                return ts == null ? null : new CardVehicleUnitUse(ts, manufacturer, device, sw);
            }, c, "EF VehicleUnits_Used"));
            vehicleUnits.sort(Comparator.comparing(CardVehicleUnitUse::timestamp));

            gnss = c.list(ef, EF_GNSS_PLACES, v -> {
                int size = recordSize(v.length - 2, id == null || id.gnssAdRecords() == null ? 0 : id.gnssAdRecords(),
                        GNSS_AD_RECORD_SIZE, GNSS_AD_RECORD_SIZE, GNSS_AD_RECORD_SIZE + 1);
                return fixedRecords(v, 2, size, r -> {
                    Instant ts = r.timeReal();
                    GnssPlace place = GnssPlace.read(r, size > GNSS_AD_RECORD_SIZE);
                    int odometer = r.u24();
                    return ts == null ? null : new CardGnssAccumulatedDriving(ts, place, odometer);
                }, c, "EF GNSS_Places");
            });
            gnss.sort(Comparator.comparing(CardGnssAccumulatedDriving::timestamp));

            borders = c.list(ef, EF_BORDER_CROSSINGS, v -> fixedRecords(v, 2, BORDER_CROSSING_RECORD_SIZE, r -> {
                Nation left = Nation.of(r.u8());
                Nation entered = Nation.of(r.u8());
                GnssPlace place = GnssPlace.read(r, true);
                int odometer = r.u24();
                return place.timestamp() == null ? null : new CardBorderCrossing(left, entered, place, odometer);
            }, c, "EF Border_Crossings"));
            borders.sort(Comparator.comparing(b -> b.place().timestamp()));

            loads = c.list(ef, EF_LOAD_UNLOAD_OPERATIONS, v -> fixedRecords(v, 2, LOAD_UNLOAD_RECORD_SIZE, r -> {
                Instant ts = r.timeReal();
                int type = r.u8();
                GnssPlace place = GnssPlace.read(r, true);
                int odometer = r.u24();
                return ts == null ? null : new CardLoadUnload(ts, type, place, odometer);
            }, c, "EF Load_Unload_Operations"));
            loads.sort(Comparator.comparing(CardLoadUnload::timestamp));

            loadTypes = c.list(ef, EF_LOAD_TYPE_ENTRIES, v -> fixedRecords(v, 2, LOAD_TYPE_ENTRY_RECORD_SIZE, r -> {
                Instant ts = r.timeReal();
                int type = r.u8();
                return ts == null ? null : new CardLoadTypeEntry(ts, type);
            }, c, "EF Load_Type_Entries"));
            loadTypes.sort(Comparator.comparing(CardLoadTypeEntry::timestamp));
        }

        return new CardApplication(gen, appId, identification[0], holder, licence, lastDownload, List.copyOf(events),
                List.copyOf(faults), List.copyOf(days), List.copyOf(vehicles), List.copyOf(places), currentUse,
                control, List.copyOf(conditions), List.copyOf(vehicleUnits), List.copyOf(gnss),
                List.copyOf(borders), List.copyOf(loads), List.copyOf(loadTypes));
    }

    private static CardIcc parseIcc(byte[] v) {
        ByteReader r = new ByteReader(v);
        int clockStop = r.u8();
        ExtendedSerialNumber serial = ExtendedSerialNumber.read(r);
        String approval = r.ia5(8);
        int personaliser = r.u8();
        return new CardIcc(clockStop, serial, approval, personaliser);
    }

    private static CardApplicationIdentification parseApplicationIdentification(byte[] v) {
        ByteReader r = new ByteReader(v);
        int type = r.u8();
        int version = r.u16();
        int events = r.u8();
        int faults = r.u8();
        int activityLength = r.u16();
        int vehicles = r.u16();
        if (v.length < 17) {
            // GEN1: NoOfCardPlaceRecords is one byte
            return new CardApplicationIdentification(type, version, events, faults, activityLength, vehicles,
                    r.u8(), null, null, null, null, null, null);
        }
        return new CardApplicationIdentification(type, version, events, faults, activityLength, vehicles,
                r.u16(), r.u16(), r.u16(), r.u16(), null, null, null);
    }

    private static CardEventFault eventFault(ByteReader r) {
        int type = r.u8();
        Instant begin = r.timeReal();
        Instant end = r.timeReal();
        VehicleRegistration vehicle = VehicleRegistration.read(r);
        return begin == null ? null : new CardEventFault(type, begin, end, vehicle);
    }

    private static CardVehicleUse vehicleUse(ByteReader r, boolean g2) {
        int odoBegin = r.u24();
        int odoEnd = r.u24();
        Instant first = r.timeReal();
        Instant last = r.timeReal();
        VehicleRegistration registration = VehicleRegistration.read(r);
        int counter = r.bcd(2);
        String vin = g2 ? r.ia5(17) : null;
        return first == null ? null : new CardVehicleUse(odoBegin, odoEnd, first, last, registration, counter, vin);
    }

    /**
     * EF Driver_Activity_Data: activityPointerOldestDayRecord, activityPointerNewestRecord and a cyclic buffer of
     * day records (previous record length, record length, date, presence counter, distance, activity changes).
     * The records are walked from the oldest to the newest; a record may wrap around the end of the buffer.
     */
    static List<CardActivityDay> activity(byte[] v, Ctx c) {
        ByteReader head = new ByteReader(v);
        int oldest = head.u16();
        int newest = head.u16();
        byte[] buf = head.bytes(head.remaining());
        int n = buf.length;
        List<CardActivityDay> days = new ArrayList<>();
        if (n < 12) {
            return days;
        }
        if (oldest >= n || newest >= n) {
            c.warn("EF Driver_Activity_Data: pointer outside the buffer");
            return days;
        }
        TreeMap<LocalDate, CardActivityDay> byDate = new TreeMap<>();
        int p = oldest;
        int guard = n / 12 + 2;
        while (guard-- > 0) {
            int recordLength = cyclicU16(buf, p + 2);
            if (recordLength == 0 && p == oldest && byDate.isEmpty()) {
                break; // never written
            }
            if (recordLength < 12 || recordLength > n) {
                c.warn("EF Driver_Activity_Data: bad record length " + recordLength + " at " + p);
                break;
            }
            byte[] rec = new byte[recordLength];
            for (int i = 0; i < recordLength; i++) {
                rec[i] = buf[(p + i) % n];
            }
            ByteReader r = new ByteReader(rec);
            r.skip(4);
            Instant date = r.timeReal();
            int presence = r.bcd(2);
            int distance = r.u16();
            List<ActivityChange> changes = new ArrayList<>((recordLength - 12) / 2);
            while (r.remaining() >= 2) {
                changes.add(ActivityChange.decode(r.u16()));
            }
            if (date != null) {
                LocalDate day = LocalDate.ofInstant(date, ZoneOffset.UTC);
                byDate.put(day, new CardActivityDay(day, presence, distance, List.copyOf(changes)));
            }
            if (p == newest) {
                break;
            }
            p = (p + recordLength) % n;
        }
        if (guard < 0) {
            c.warn("EF Driver_Activity_Data: newest record not reached");
        }
        days.addAll(byDate.values());
        return days;
    }

    private static int cyclicU16(byte[] buf, int p) {
        int n = buf.length;
        return ((buf[p % n] & 0xFF) << 8) | (buf[(p + 1) % n] & 0xFF);
    }

    /** Records of a fixed size after a pointer of {@code pointerSize} bytes; null results are left out. */
    private static <T> List<T> fixedRecords(byte[] v, int pointerSize, int recordSize, Function<ByteReader, T> reader,
                                            Ctx c, String what) {
        List<T> result = new ArrayList<>();
        int body = v.length - pointerSize;
        if (body < 0) {
            c.warn(what + ": too short");
            return result;
        }
        if (body % recordSize != 0) {
            c.warn(what + ": length " + v.length + " is not a multiple of the record size " + recordSize);
        }
        ByteReader r = new ByteReader(v);
        r.skip(pointerSize);
        while (r.remaining() >= recordSize) {
            T item = reader.apply(r.slice(recordSize));
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }

    /** Record size from the EF length and the capacity in the application identification, if it is an allowed one. */
    private static int recordSize(int bodyLength, int capacity, int fallback, int... allowed) {
        if (capacity > 0 && bodyLength % capacity == 0) {
            int size = bodyLength / capacity;
            for (int a : allowed) {
                if (a == size) {
                    return size;
                }
            }
        }
        for (int a : allowed) {
            if (bodyLength % a == 0) {
                return a;
            }
        }
        return fallback;
    }

    private static <T> T guard(List<String> warnings, String what, Supplier<T> s) {
        try {
            return s.get();
        } catch (TachoFormatException e) {
            warnings.add(what + ": " + e.getMessage());
            return null;
        }
    }

    private static String hex4(int v) {
        return String.format("%04X", v);
    }

    /** Parsing context of one application: warnings carry the DF name and the EF id. */
    static final class Ctx {
        private final String df;
        private final List<String> warnings;

        Ctx(String df, List<String> warnings) {
            this.df = df;
            this.warnings = warnings;
        }

        void warn(String message) {
            warnings.add(df + " " + message);
        }

        <T> T parse(Map<Integer, byte[]> ef, int fid, Function<byte[], T> parser) {
            byte[] v = ef.get(fid);
            if (v == null) {
                return null;
            }
            try {
                return parser.apply(v);
            } catch (TachoFormatException e) {
                warn("EF " + hex4(fid) + ": " + e.getMessage());
                return null;
            }
        }

        <T> List<T> list(Map<Integer, byte[]> ef, int fid, Function<byte[], List<T>> parser) {
            List<T> result = parse(ef, fid, parser);
            return result == null ? new ArrayList<>() : new ArrayList<>(result);
        }
    }
}

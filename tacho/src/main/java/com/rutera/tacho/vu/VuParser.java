package com.rutera.tacho.vu;

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
import com.rutera.tacho.model.Slot;
import com.rutera.tacho.model.SpecificCondition;
import com.rutera.tacho.model.VehicleRegistration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Vehicle unit download (Appendix 7): a sequence of transfers, each starting with 76h and the TREP of the transfer.
 * GEN1 transfers (01h..05h) are fixed structures with counters; second generation transfers (21h..25h, version 2
 * 31h..35h) are record arrays ending with a signature array. Signatures and certificates are skipped for now.
 */
public final class VuParser {

    private static final int SID_POSITIVE_RESPONSE = 0x76;

    private static final int CERTIFICATE_SIZE_GEN1 = 194;
    private static final int SIGNATURE_SIZE_GEN1 = 128;
    private static final int FULL_CARD_NUMBER_AND_GENERATION_SIZE = 19;

    private VuParser() {
    }

    public static boolean looksLikeVu(byte[] data) {
        return data.length >= 2 && (data[0] & 0xFF) == SID_POSITIVE_RESPONSE && generationOf(data[1] & 0xFF) != null;
    }

    /** Generation of a TREP, or null if it is not a VU transfer. */
    static Generation generationOf(int trep) {
        if (trep >= 0x01 && trep <= 0x05) {
            return Generation.GEN1;
        }
        if (trep >= 0x21 && trep <= 0x25) {
            return Generation.GEN2;
        }
        if (trep >= 0x31 && trep <= 0x35) {
            return Generation.GEN2V2;
        }
        return null;
    }

    public static VuFile parse(byte[] data) {
        if (!looksLikeVu(data)) {
            throw new TachoFormatException(Reason.NOT_A_TACHOGRAPH_FILE, "Not a vehicle unit download");
        }
        Builder b = new Builder();
        ByteReader r = new ByteReader(data);
        while (r.hasRemaining()) {
            int offset = r.absolutePosition();
            int sid = r.u8();
            int trep = r.hasRemaining() ? r.u8() : -1;
            Generation gen = generationOf(trep);
            if (sid != SID_POSITIVE_RESPONSE || gen == null) {
                b.warnings.add((data.length - offset) + " bytes after the last transfer ignored (offset " + offset
                        + ")");
                break;
            }
            try {
                switch (trep & 0x0F) {
                    case 0x01 -> b.overview(gen == Generation.GEN1 ? overviewGen1(r) : overviewGen2(r, gen, b));
                    case 0x02 -> b.activityDays.add(gen == Generation.GEN1 ? activitiesGen1(r)
                            : activitiesGen2(r, gen, b));
                    case 0x03 -> b.eventsAndFaults(gen == Generation.GEN1 ? eventsGen1(r) : eventsGen2(r, b));
                    case 0x04 -> b.speed.addAll(gen == Generation.GEN1 ? speedGen1(r) : speedGen2(r));
                    case 0x05 -> b.technical(gen == Generation.GEN1 ? technicalGen1(r) : technicalGen2(r, b));
                    default -> throw new IllegalStateException();
                }
                b.generation(gen);
            } catch (TachoFormatException e) {
                if (b.generation == null) {
                    throw e;
                }
                b.warnings.add(String.format("Transfer %02Xh at offset %d: %s; the rest of the file is ignored",
                        trep, offset, e.getMessage()));
                break;
            }
        }
        b.activityDays.sort(Comparator.comparing(VuActivityDay::date,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        if (!b.extraEvents.isEmpty()) {
            VuEventsAndFaults e = b.eventsAndFaults;
            List<VuEventFault> events = new ArrayList<>(e == null ? List.of() : e.events());
            events.addAll(b.extraEvents);
            b.eventsAndFaults = new VuEventsAndFaults(e == null ? List.of() : e.faults(), List.copyOf(events),
                    e == null ? null : e.overSpeedingControl(), e == null ? List.of() : e.overSpeedingEvents(),
                    e == null ? List.of() : e.timeAdjustments());
        }
        return new VuFile(b.generation, b.overview, List.copyOf(b.activityDays), b.eventsAndFaults,
                List.copyOf(b.speed), b.technical, List.copyOf(b.warnings));
    }

    // ---------------------------------------------------------------- first generation

    private static VuOverview overviewGen1(ByteReader r) {
        r.skip(CERTIFICATE_SIZE_GEN1); // MemberStateCertificate
        r.skip(CERTIFICATE_SIZE_GEN1); // VuCertificate
        String vin = r.ia5(17);
        VehicleRegistration registration = VehicleRegistration.read(r);
        Instant current = r.timeReal();
        Instant periodBegin = r.timeReal();
        Instant periodEnd = r.timeReal();
        int slots = r.u8();
        VuDownloadActivity previous = downloadActivity(r, false);
        List<VuCompanyLock> locks = counted(r, r.u8(), x -> companyLock(x, false));
        List<VuControlActivity> controls = counted(r, r.u8(), x -> controlActivity(x, false));
        r.skip(SIGNATURE_SIZE_GEN1);
        return new VuOverview(Generation.GEN1, vin, registration, current, periodBegin, periodEnd, slots & 0x0F,
                (slots >> 4) & 0x0F, previous, locks, controls);
    }

    private static VuActivityDay activitiesGen1(ByteReader r) {
        Instant day = r.timeReal();
        int odometer = r.u24();
        List<VuCardInsertion> insertions = counted(r, r.u16(), x -> cardInsertion(x, false));
        int changeCount = r.u16();
        List<ActivityChange> changes = new ArrayList<>(changeCount);
        for (int i = 0; i < changeCount; i++) {
            changes.add(ActivityChange.decode(r.u16()));
        }
        List<VuPlace> places = counted(r, r.u8(), x -> new VuPlace(CardNumber.read(x),
                PlaceRecord.read(x, PlaceRecord.SIZE_GEN1)));
        List<SpecificCondition> conditions = counted(r, r.u16(), SpecificCondition::read);
        r.skip(SIGNATURE_SIZE_GEN1);
        return new VuActivityDay(Generation.GEN1, date(day), odometer, insertions, List.copyOf(changes), places,
                List.of(), conditions, List.of(), List.of());
    }

    private static VuEventsAndFaults eventsGen1(ByteReader r) {
        List<VuEventFault> faults = counted(r, r.u8(), x -> eventFault(x, false, false));
        List<VuEventFault> events = counted(r, r.u8(), x -> eventFault(x, false, true));
        VuOverSpeedingControl control = new VuOverSpeedingControl(r.timeReal(), r.timeReal(), r.u8());
        List<VuOverSpeedingEvent> overSpeeding = counted(r, r.u8(), x -> overSpeedingEvent(x, false));
        List<VuTimeAdjustment> adjustments = counted(r, r.u8(), x -> timeAdjustment(x, false));
        r.skip(SIGNATURE_SIZE_GEN1);
        return new VuEventsAndFaults(faults, events, control, overSpeeding, adjustments);
    }

    private static List<VuSpeedBlock> speedGen1(ByteReader r) {
        List<VuSpeedBlock> blocks = counted(r, r.u16(), VuParser::speedBlock);
        r.skip(SIGNATURE_SIZE_GEN1);
        return blocks;
    }

    private static VuTechnicalData technicalGen1(ByteReader r) {
        VuIdentification identification = new VuIdentification(r.name(), r.name(), r.ia5(16),
                ExtendedSerialNumber.read(r), r.ia5(4), r.timeReal(), r.timeReal(), r.ia5(8), null, null, null);
        VuSensor sensor = new VuSensor(ExtendedSerialNumber.read(r), r.ia5(8), r.timeReal());
        List<VuCalibration> calibrations = counted(r, r.u8(), VuParser::calibration);
        r.skip(SIGNATURE_SIZE_GEN1);
        return new VuTechnicalData(identification, List.of(sensor), List.of(), calibrations);
    }

    // ---------------------------------------------------------------- second generation

    private static VuOverview overviewGen2(ByteReader r, Generation gen, Builder b) {
        String vin = null;
        VehicleRegistration registration = null;
        Instant current = null;
        Instant periodBegin = null;
        Instant periodEnd = null;
        int slots = 0;
        VuDownloadActivity previous = null;
        List<VuCompanyLock> locks = List.of();
        List<VuControlActivity> controls = List.of();
        for (RecordArray a : RecordArray.readTransfer(r)) {
            ByteReader first = a.first();
            switch (a.type()) {
                case RecordArray.MEMBER_STATE_CERTIFICATE, RecordArray.VU_CERTIFICATE, RecordArray.SIGNATURE -> {
                }
                case RecordArray.VEHICLE_IDENTIFICATION_NUMBER -> vin = first == null ? null : first.ia5(17);
                case RecordArray.VEHICLE_REGISTRATION_NUMBER ->
                        registration = first == null ? null : VehicleRegistration.readNumberOnly(first);
                case RecordArray.VEHICLE_REGISTRATION_IDENTIFICATION ->
                        registration = first == null ? null : VehicleRegistration.read(first);
                case RecordArray.CURRENT_DATE_TIME -> current = first == null ? null : first.timeReal();
                case RecordArray.VU_DOWNLOADABLE_PERIOD -> {
                    if (first != null) {
                        periodBegin = first.timeReal();
                        periodEnd = first.timeReal();
                    }
                }
                case RecordArray.CARD_SLOTS_STATUS -> slots = first == null ? 0 : first.u8();
                case RecordArray.VU_DOWNLOAD_ACTIVITY_DATA ->
                        previous = first == null ? null : downloadActivity(first, true);
                case RecordArray.VU_COMPANY_LOCKS_RECORD -> locks = records(a, x -> companyLock(x, true));
                case RecordArray.VU_CONTROL_ACTIVITY_RECORD -> controls = records(a, x -> controlActivity(x, true));
                default -> b.unexpected(a, "overview");
            }
        }
        return new VuOverview(gen, vin, registration, current, periodBegin, periodEnd, slots & 0x0F,
                (slots >> 4) & 0x0F, previous, locks, controls);
    }

    private static VuActivityDay activitiesGen2(ByteReader r, Generation gen, Builder b) {
        LocalDate day = null;
        Integer odometer = null;
        List<VuCardInsertion> insertions = List.of();
        List<ActivityChange> changes = new ArrayList<>();
        List<VuPlace> places = List.of();
        List<VuGnssAccumulatedDriving> gnss = List.of();
        List<SpecificCondition> conditions = List.of();
        List<VuBorderCrossing> borders = List.of();
        List<VuLoadUnload> loads = List.of();
        for (RecordArray a : RecordArray.readTransfer(r)) {
            ByteReader first = a.first();
            switch (a.type()) {
                case RecordArray.SIGNATURE -> {
                }
                case RecordArray.DATE_OF_DAY_DOWNLOADED -> day = first == null ? null : date(first.timeReal());
                case RecordArray.ODOMETER_VALUE_MIDNIGHT -> odometer = first == null ? null : first.u24();
                case RecordArray.VU_CARD_IW_RECORD -> insertions = records(a, x -> cardInsertion(x, true));
                case RecordArray.ACTIVITY_CHANGE_INFO -> {
                    for (ByteReader x : a.records()) {
                        changes.add(ActivityChange.decode(x.u16()));
                    }
                }
                case RecordArray.VU_PLACE_DAILY_WORK_PERIOD_RECORD -> places = records(a, x -> new VuPlace(
                        CardNumber.readWithGeneration(x),
                        PlaceRecord.read(x, a.size() - FULL_CARD_NUMBER_AND_GENERATION_SIZE)));
                case RecordArray.VU_GNSS_AD_RECORD -> gnss = records(a, x -> new VuGnssAccumulatedDriving(
                        x.timeReal(), CardNumber.readWithGeneration(x), CardNumber.readWithGeneration(x),
                        GnssPlace.read(x, a.size() > 56), x.u24()));
                case RecordArray.SPECIFIC_CONDITION_RECORD -> conditions = records(a, SpecificCondition::read);
                case RecordArray.VU_BORDER_CROSSING_RECORD -> borders = records(a, x -> new VuBorderCrossing(
                        CardNumber.readWithGeneration(x), CardNumber.readWithGeneration(x), Nation.of(x.u8()),
                        Nation.of(x.u8()), GnssPlace.read(x, true), x.u24()));
                case RecordArray.VU_LOAD_UNLOAD_RECORD -> loads = records(a, x -> new VuLoadUnload(x.timeReal(),
                        x.u8(), CardNumber.readWithGeneration(x), CardNumber.readWithGeneration(x),
                        GnssPlace.read(x, true), x.u24()));
                default -> b.unexpected(a, "activities");
            }
        }
        return new VuActivityDay(gen, day, odometer, insertions, List.copyOf(changes), places, gnss, conditions,
                borders, loads);
    }

    private static VuEventsAndFaults eventsGen2(ByteReader r, Builder b) {
        List<VuEventFault> faults = new ArrayList<>();
        List<VuEventFault> events = new ArrayList<>();
        VuOverSpeedingControl control = null;
        List<VuOverSpeedingEvent> overSpeeding = List.of();
        List<VuTimeAdjustment> adjustments = List.of();
        for (RecordArray a : RecordArray.readTransfer(r)) {
            ByteReader first = a.first();
            switch (a.type()) {
                case RecordArray.SIGNATURE, RecordArray.VU_TIME_ADJUSTMENT_GNSS_RECORD -> {
                }
                case RecordArray.VU_FAULT_RECORD -> faults.addAll(records(a, x -> eventFault(x, true, false)));
                case RecordArray.VU_EVENT_RECORD, RecordArray.VU_POWER_SUPPLY_INTERRUPTION_RECORD ->
                        events.addAll(records(a, x -> eventFault(x, true, true)));
                case RecordArray.VU_OVER_SPEEDING_CONTROL_DATA -> control = first == null ? null
                        : new VuOverSpeedingControl(first.timeReal(), first.timeReal(), first.u8());
                case RecordArray.VU_OVER_SPEEDING_EVENT_RECORD ->
                        overSpeeding = records(a, x -> overSpeedingEvent(x, true));
                case RecordArray.VU_TIME_ADJUSTMENT_RECORD -> adjustments = records(a, x -> timeAdjustment(x, true));
                default -> b.unexpected(a, "events and faults");
            }
        }
        return new VuEventsAndFaults(List.copyOf(faults), List.copyOf(events), control, overSpeeding, adjustments);
    }

    private static List<VuSpeedBlock> speedGen2(ByteReader r) {
        List<VuSpeedBlock> blocks = new ArrayList<>();
        for (RecordArray a : RecordArray.readTransfer(r)) {
            if (a.type() == RecordArray.VU_DETAILED_SPEED_BLOCK) {
                blocks.addAll(records(a, VuParser::speedBlock));
            }
        }
        return blocks;
    }

    private static VuTechnicalData technicalGen2(ByteReader r, Builder b) {
        VuIdentification identification = null;
        List<VuSensor> sensors = new ArrayList<>();
        List<VuSensor> gnss = new ArrayList<>();
        List<VuCalibration> calibrations = List.of();
        for (RecordArray a : RecordArray.readTransfer(r)) {
            ByteReader first = a.first();
            switch (a.type()) {
                case RecordArray.SIGNATURE, RecordArray.VU_CARD_RECORD, RecordArray.VU_ITS_CONSENT_RECORD -> {
                }
                case RecordArray.VU_IDENTIFICATION -> identification = first == null ? null : identificationGen2(first);
                case RecordArray.SENSOR_PAIRED_RECORD, RecordArray.SENSOR_PAIRED ->
                        sensors.addAll(records(a, x -> sensor(x, a.size())));
                case RecordArray.SENSOR_EXTERNAL_GNSS_COUPLED_RECORD ->
                        gnss.addAll(records(a, x -> sensor(x, a.size())));
                case RecordArray.VU_CALIBRATION_RECORD -> calibrations = records(a, VuParser::calibration);
                case RecordArray.VU_POWER_SUPPLY_INTERRUPTION_RECORD ->
                        b.extraEvents.addAll(records(a, x -> eventFault(x, true, true)));
                default -> b.unexpected(a, "technical data");
            }
        }
        return new VuTechnicalData(identification, List.copyOf(sensors), List.copyOf(gnss), calibrations);
    }

    private static VuIdentification identificationGen2(ByteReader r) {
        String name = r.name();
        String address = r.name();
        String partNumber = r.ia5(16);
        ExtendedSerialNumber serial = ExtendedSerialNumber.read(r);
        String software = r.ia5(4);
        Instant installed = r.timeReal();
        Instant manufactured = r.timeReal();
        String approval = r.ia5(16);
        Integer generation = r.hasRemaining() ? r.u8() : null;
        Integer ability = r.hasRemaining() ? r.u8() : null;
        String map = r.remaining() >= 12 ? r.ia5(12) : null;
        return new VuIdentification(name, address, partNumber, serial, software, installed, manufactured, approval,
                generation, ability, map);
    }

    // ---------------------------------------------------------------- records of both generations

    private static CardNumber card(ByteReader r, boolean g2) {
        return g2 ? CardNumber.readWithGeneration(r) : CardNumber.read(r);
    }

    private static VuDownloadActivity downloadActivity(ByteReader r, boolean g2) {
        Instant time = r.timeReal();
        CardNumber card = card(r, g2);
        String name = r.name();
        return time == null && card == null ? null : new VuDownloadActivity(time, card, name);
    }

    private static VuCompanyLock companyLock(ByteReader r, boolean g2) {
        return new VuCompanyLock(r.timeReal(), r.timeReal(), r.name(), r.name(), card(r, g2));
    }

    private static VuControlActivity controlActivity(ByteReader r, boolean g2) {
        return new VuControlActivity(r.u8(), r.timeReal(), card(r, g2), r.timeReal(), r.timeReal());
    }

    private static VuCardInsertion cardInsertion(ByteReader r, boolean g2) {
        String surname = r.name();
        String firstNames = r.name();
        CardNumber card = card(r, g2);
        Instant expiry = r.timeReal();
        Instant inserted = r.timeReal();
        int odoIn = r.u24();
        int slot = r.u8();
        Instant withdrawn = r.timeReal();
        int odoOut = r.u24();
        VehicleRegistration previous = VehicleRegistration.read(r);
        Instant previousWithdrawal = r.timeReal();
        if (g2) {
            r.u8(); // vuGeneration of the previous vehicle
        }
        boolean manual = r.u8() != 0;
        return new VuCardInsertion(surname, firstNames, card, expiry, inserted, odoIn,
                slot == 1 ? Slot.CO_DRIVER : Slot.DRIVER, withdrawn, withdrawn == null ? null : odoOut, previous,
                previousWithdrawal, manual);
    }

    private static VuEventFault eventFault(ByteReader r, boolean g2, boolean event) {
        int type = r.u8();
        int purpose = r.u8();
        Instant begin = r.timeReal();
        Instant end = r.timeReal();
        CardNumber driverBegin = card(r, g2);
        CardNumber coDriverBegin = card(r, g2);
        CardNumber driverEnd = card(r, g2);
        CardNumber coDriverEnd = card(r, g2);
        Integer similar = event && r.hasRemaining() ? r.u8() : null;
        return new VuEventFault(type, purpose, begin, end, driverBegin, coDriverBegin, driverEnd, coDriverEnd,
                similar);
    }

    private static VuOverSpeedingEvent overSpeedingEvent(ByteReader r, boolean g2) {
        return new VuOverSpeedingEvent(r.u8(), r.u8(), r.timeReal(), r.timeReal(), r.u8(), r.u8(), card(r, g2),
                r.u8());
    }

    private static VuTimeAdjustment timeAdjustment(ByteReader r, boolean g2) {
        return new VuTimeAdjustment(r.timeReal(), r.timeReal(), r.name(), r.name(), card(r, g2));
    }

    private static VuSpeedBlock speedBlock(ByteReader r) {
        Instant begin = r.timeReal();
        int[] speeds = new int[60];
        for (int i = 0; i < speeds.length; i++) {
            speeds[i] = r.u8();
        }
        return new VuSpeedBlock(begin, speeds);
    }

    /** SensorPaired (GEN1: approval 8 bytes) or SensorPairedRecord / external GNSS (approval 16 bytes). */
    private static VuSensor sensor(ByteReader r, int size) {
        ExtendedSerialNumber serial = ExtendedSerialNumber.read(r);
        String approval = r.ia5(size - 12);
        return new VuSensor(serial, approval, r.timeReal());
    }

    /** The 167 bytes common to all generations; the second generation appends seals and serial numbers. */
    private static VuCalibration calibration(ByteReader r) {
        int purpose = r.u8();
        String workshopName = r.name();
        String workshopAddress = r.name();
        CardNumber workshopCard = CardNumber.read(r);
        Instant workshopCardExpiry = r.timeReal();
        String vin = r.ia5(17);
        VehicleRegistration registration = VehicleRegistration.read(r);
        int w = r.u16();
        int k = r.u16();
        int l = r.u16();
        String tyre = r.ia5(15);
        int speed = r.u8();
        int oldOdometer = r.u24();
        int newOdometer = r.u24();
        Instant oldTime = r.timeReal();
        Instant newTime = r.timeReal();
        Instant next = r.timeReal();
        return new VuCalibration(purpose, workshopName, workshopAddress, workshopCard, workshopCardExpiry, vin,
                registration, w, k, l, tyre, speed, oldOdometer, newOdometer, oldTime, newTime, next);
    }

    private static <T> List<T> counted(ByteReader r, int count, Function<ByteReader, T> reader) {
        List<T> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(reader.apply(r));
        }
        return List.copyOf(list);
    }

    private static <T> List<T> records(RecordArray a, Function<ByteReader, T> reader) {
        List<T> list = new ArrayList<>(a.count());
        for (ByteReader x : a.records()) {
            list.add(reader.apply(x));
        }
        return List.copyOf(list);
    }

    private static LocalDate date(Instant t) {
        return t == null ? null : LocalDate.ofInstant(t, ZoneOffset.UTC);
    }

    private static final class Builder {
        Generation generation;
        VuOverview overview;
        final List<VuActivityDay> activityDays = new ArrayList<>();
        VuEventsAndFaults eventsAndFaults;
        final List<VuSpeedBlock> speed = new ArrayList<>();
        VuTechnicalData technical;
        final List<VuEventFault> extraEvents = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        void generation(Generation g) {
            if (generation == null || g.ordinal() > generation.ordinal()) {
                generation = g;
            }
        }

        void overview(VuOverview o) {
            if (overview == null) {
                overview = o;
            } else {
                warnings.add("More than one overview transfer, the first one is used");
            }
        }

        void eventsAndFaults(VuEventsAndFaults e) {
            if (eventsAndFaults == null) {
                eventsAndFaults = e;
            } else {
                warnings.add("More than one events and faults transfer, the first one is used");
            }
        }

        void technical(VuTechnicalData t) {
            if (technical == null) {
                technical = t;
            } else {
                warnings.add("More than one technical data transfer, the first one is used");
            }
        }

        void unexpected(RecordArray a, String transfer) {
            warnings.add(String.format("Unexpected record array %02Xh in %s at offset %d, skipped", a.type(),
                    transfer, a.offset()));
        }
    }
}

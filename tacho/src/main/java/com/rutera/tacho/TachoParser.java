package com.rutera.tacho;

import com.rutera.tacho.TachoFormatException.Reason;
import com.rutera.tacho.card.DriverCardFile;
import com.rutera.tacho.card.DriverCardParser;
import com.rutera.tacho.vu.VuFile;
import com.rutera.tacho.vu.VuParser;

/**
 * Entry point: recognises and parses DDD files (also known as .ddd, .esm, .tgd, .c1b, .v1b). Thread-safe and
 * stateless; signatures are not verified yet.
 *
 * <pre>{@code
 * TachoFile file = TachoParser.parse(bytes);
 * if (file instanceof DriverCardFile card) { ... } else if (file instanceof VuFile vu) { ... }
 * }</pre>
 */
public final class TachoParser {

    private TachoParser() {
    }

    /** Recognises the kind of file by its structure; throws NOT_A_TACHOGRAPH_FILE otherwise. */
    public static FileKind detect(byte[] data) {
        if (VuParser.looksLikeVu(data)) {
            return FileKind.VEHICLE_UNIT;
        }
        if (DriverCardParser.looksLikeCard(data)) {
            return FileKind.DRIVER_CARD;
        }
        throw new TachoFormatException(Reason.NOT_A_TACHOGRAPH_FILE,
                "Neither a card download nor a vehicle unit download");
    }

    public static TachoFile parse(byte[] data) {
        return switch (detect(data)) {
            case DRIVER_CARD -> DriverCardParser.parse(data);
            case VEHICLE_UNIT -> VuParser.parse(data);
        };
    }

    public static DriverCardFile parseDriverCard(byte[] data) {
        return DriverCardParser.parse(data);
    }

    public static VuFile parseVehicleUnit(byte[] data) {
        return VuParser.parse(data);
    }
}

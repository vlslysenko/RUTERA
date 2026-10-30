package com.rutera.tacho.model;

import com.rutera.tacho.io.ByteReader;

import java.time.Instant;

/**
 * SpecificConditionRecord (5 bytes).
 *
 * @param type 1 out of scope begin, 2 out of scope end, 3 ferry/train crossing begin, 4 ferry/train crossing end
 */
public record SpecificCondition(Instant time, int type) {

    public static final int SIZE = 5;

    public static SpecificCondition read(ByteReader r) {
        return new SpecificCondition(r.timeReal(), r.u8());
    }

    public String typeKey() {
        return switch (type) {
            case 1 -> "OUT_OF_SCOPE_BEGIN";
            case 2 -> "OUT_OF_SCOPE_END";
            case 3 -> "FERRY_TRAIN_BEGIN";
            case 4 -> "FERRY_TRAIN_END";
            default -> "UNKNOWN_" + type;
        };
    }
}

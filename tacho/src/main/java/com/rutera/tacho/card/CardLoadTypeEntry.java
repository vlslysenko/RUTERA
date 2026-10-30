package com.rutera.tacho.card;

import java.time.Instant;

/**
 * CardLoadTypeEntryRecord of EF Load_Type_Entries (0530h, GEN2V2).
 *
 * @param loadType 0 not defined, 1 goods, 2 passengers
 */
public record CardLoadTypeEntry(Instant timestamp, int loadType) {
}

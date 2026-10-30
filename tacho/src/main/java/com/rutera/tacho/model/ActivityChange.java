package com.rutera.tacho.model;

/**
 * ActivityChangeInfo (2 bytes, bits 'scpaattttttttttt'): an activity that starts at {@code minuteOfDay} (UTC) and
 * lasts until the next change of the same slot or the end of the day.
 *
 * @param crew bit 'c': with the card inserted, false single and true crew driving; on a driver card record with
 *             {@code cardPresent == false}, true means the activity was entered manually
 * @param cardPresent bit 'p' == 0: the card was inserted
 */
public record ActivityChange(Slot slot, boolean crew, boolean cardPresent, Activity activity, int minuteOfDay) {

    public static ActivityChange decode(int value) {
        return new ActivityChange(
                (value & 0x8000) != 0 ? Slot.CO_DRIVER : Slot.DRIVER,
                (value & 0x4000) != 0,
                (value & 0x2000) == 0,
                Activity.values()[(value >> 11) & 0x03],
                value & 0x07FF);
    }

    public int encode() {
        return (slot == Slot.CO_DRIVER ? 0x8000 : 0) | (crew ? 0x4000 : 0) | (cardPresent ? 0 : 0x2000)
                | (activity.ordinal() << 11) | (minuteOfDay & 0x07FF);
    }

    /** Driver card only: a period without the card, entered manually by the driver. */
    public boolean isManualEntry() {
        return !cardPresent && crew;
    }
}

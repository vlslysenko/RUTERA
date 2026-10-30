package com.rutera.tacho;

/**
 * The file is not a tachograph download or cannot be read. {@link #getReason()} is stable and can be mapped to an
 * API error code; the message is for logs and may contain offsets, never personal data.
 */
public class TachoFormatException extends RuntimeException {

    public enum Reason {
        /** Neither a driver card download (tag/length/value) nor a vehicle unit download (TREP blocks). */
        NOT_A_TACHOGRAPH_FILE,
        /** The file ends inside a structure. */
        TRUNCATED,
        /** A valid file of a kind that is not supported yet, for example a company or workshop card. */
        UNSUPPORTED,
        /** A structure has impossible values (lengths, pointers). */
        CORRUPT
    }

    private final Reason reason;
    private final int offset;

    public TachoFormatException(Reason reason, String message) {
        this(reason, message, -1);
    }

    public TachoFormatException(Reason reason, String message, int offset) {
        super(offset >= 0 ? message + " (offset " + offset + ")" : message);
        this.reason = reason;
        this.offset = offset;
    }

    public Reason getReason() {
        return reason;
    }

    /** Absolute offset in the file where the problem was found, or -1. */
    public int getOffset() {
        return offset;
    }
}

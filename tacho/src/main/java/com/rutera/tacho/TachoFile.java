package com.rutera.tacho;

import java.util.List;

/**
 * A parsed DDD file: {@link com.rutera.tacho.card.DriverCardFile} or {@link com.rutera.tacho.vu.VuFile} (not
 * sealed: the module also runs from the classpath, where a sealed type may not span packages).
 */
public interface TachoFile {

    FileKind kind();

    /** The highest generation found in the file. */
    Generation generation();

    /** Problems that did not stop the parsing (a damaged cyclic file, unknown record types, trailing bytes). */
    List<String> warnings();

    /** The keys the import needs: who or which vehicle, which period, when it was downloaded. */
    TachoFileSummary summary();
}

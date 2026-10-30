package com.rutera.tacho.vu;

import com.rutera.tacho.model.CardNumber;

import java.time.Instant;

/** VuCompanyLocksRecord: a company lock-in, open while {@code lockOut} is null. */
public record VuCompanyLock(Instant lockIn, Instant lockOut, String companyName, String companyAddress,
                            CardNumber companyCard) {
}

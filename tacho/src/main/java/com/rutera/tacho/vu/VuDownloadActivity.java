package com.rutera.tacho.vu;

import com.rutera.tacho.model.CardNumber;

import java.time.Instant;

/** VuDownloadActivityData: the previous download of this VU (the one before the file at hand). */
public record VuDownloadActivity(Instant downloadingTime, CardNumber card, String companyOrWorkshopName) {
}

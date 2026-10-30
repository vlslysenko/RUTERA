package com.rutera.tacho.vu;

import com.rutera.tacho.model.CardNumber;
import com.rutera.tacho.model.GnssPlace;
import com.rutera.tacho.model.Nation;

/** VuBorderCrossingRecord (GEN2V2). */
public record VuBorderCrossing(CardNumber driverCard, CardNumber coDriverCard, Nation countryLeft,
                               Nation countryEntered, GnssPlace place, Integer odometerKm) {
}

package com.rutera.tacho.card;

import com.rutera.tacho.model.GnssPlace;
import com.rutera.tacho.model.Nation;

/** CardBorderCrossingRecord of EF Border_Crossings (0528h, GEN2V2). */
public record CardBorderCrossing(Nation countryLeft, Nation countryEntered, GnssPlace place, Integer odometerKm) {
}

package com.rutera.tacho.vu;

import com.rutera.tacho.model.CardNumber;
import com.rutera.tacho.model.PlaceRecord;

/** VuPlaceDailyWorkPeriodRecord: a place entered with a card in this VU. */
public record VuPlace(CardNumber card, PlaceRecord place) {
}

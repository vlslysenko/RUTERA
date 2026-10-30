package com.rutera.tacho.card;

import java.time.LocalDate;

/** DriverCardHolderIdentification (second part of EF Identification, 0520h). */
public record DriverCardHolder(String surname, String firstNames, LocalDate birthDate, String preferredLanguage) {
}

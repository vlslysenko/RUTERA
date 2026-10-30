package com.rutera.tacho.card;

import com.rutera.tacho.model.Nation;

import java.time.Instant;

/**
 * CardIdentification (first part of EF Identification, 0520h).
 *
 * @param cardNumber 16 characters: driver identification (14), replacement index, renewal index
 */
public record CardIdentification(Nation issuingNation, String cardNumber, String issuingAuthority,
                                 Instant issueDate, Instant validityBegin, Instant expiryDate) {

    /** The first 14 characters of the card number: stays the same when the card is replaced or renewed. */
    public String driverIdentification() {
        return cardNumber != null && cardNumber.length() >= 14 ? cardNumber.substring(0, 14) : cardNumber;
    }
}

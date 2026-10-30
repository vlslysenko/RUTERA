package com.rutera.tacho.card;

import com.rutera.tacho.model.ExtendedSerialNumber;

/** EF ICC (FID 0002h): chip data of the card. */
public record CardIcc(int clockStop, ExtendedSerialNumber cardExtendedSerialNumber, String cardApprovalNumber,
                      int cardPersonaliserId) {
}

package com.rutera.tacho.card;

import com.rutera.tacho.model.Nation;

/** EF Driving_Licence_Info (0521h). */
public record DrivingLicence(String issuingAuthority, Nation issuingNation, String number) {
}

package com.rutera.tacho.vu;

import com.rutera.tacho.model.ExtendedSerialNumber;

import java.time.Instant;

/**
 * VuIdentification.
 *
 * @param vuGeneration second generation only (2), otherwise null
 * @param vuAbility second generation only: bit 0 set means the VU supports GEN1 cards only
 * @param digitalMapVersion GEN2V2 only
 */
public record VuIdentification(String manufacturerName, String manufacturerAddress, String partNumber,
                               ExtendedSerialNumber serialNumber, String softwareVersion,
                               Instant softwareInstallationDate, Instant manufacturingDate, String approvalNumber,
                               Integer vuGeneration, Integer vuAbility, String digitalMapVersion) {
}

package com.rutera.core.fleet.vin;

import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Offline VIN decoding for the vehicle form.
 * <pre>
 * GET /app/fleet/vin?vin=...[&amp;year=YYYY]   -> VinDecode   (fleet.vehicle/view)
 * </pre>
 * {@code year} is the optional first registration year; it drives the Euro class suggestion. An invalid VIN is not
 * an error: the answer says {@code status: INVALID_LENGTH} or {@code INVALID_CHARS}.
 */
@RestController
@RequestMapping("/app/fleet/vin")
public class VinController {

    private final VinDecoder decoder;

    public VinController(VinDecoder decoder) {
        this.decoder = decoder;
    }

    @GetMapping
    @RequiresPrivilege(Privileges.FLEET_VEHICLE_VIEW)
    public VinDecodeDto decode(@RequestParam String vin, @RequestParam(required = false) Integer year) {
        return decoder.decode(vin, year);
    }
}

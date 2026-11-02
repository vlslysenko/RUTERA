package com.rutera.core.tacho;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Driver activity and 561/2006 violations from the tachograph data.
 * <pre>
 * GET    /app/tacho/activity?driverId&from&to           -> Activity (up to 31 days)          (tacho.hos/view)
 * GET    /app/tacho/violations?driverId&from&to&severity&unacknowledged&page&size -> {items, total} (tacho.hos/view)
 * GET    /app/tacho/violations/{id}                     -> Violation                         (tacho.hos/view)
 * POST   /app/tacho/violations/{id}/ack  {note?}        -> Violation                         (tacho.violation/ack)
 * DELETE /app/tacho/violations/{id}/ack                 -> Violation                         (tacho.violation/ack)
 * </pre>
 */
@RestController
@RequestMapping({"/app/tacho", "/api/v1/tacho"})
public class TachoHosController {

    private final HosService service;

    public TachoHosController(HosService service) {
        this.service = service;
    }

    @GetMapping("/activity")
    public HosDto.Activity activity(@RequestParam(required = false) Long driverId,
                                    @RequestParam(required = false) LocalDate from,
                                    @RequestParam(required = false) LocalDate to) {
        return service.activity(driverId, from, to);
    }

    @GetMapping("/violations")
    public HosDto.Page violations(@RequestParam(required = false) Long driverId,
                                  @RequestParam(required = false) LocalDate from,
                                  @RequestParam(required = false) LocalDate to,
                                  @RequestParam(required = false) String severity,
                                  @RequestParam(defaultValue = "false") boolean unacknowledged,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        return service.violations(driverId, from, to, severity, unacknowledged, page, size);
    }

    @GetMapping("/violations/{id}")
    public HosDto.Violation violation(@PathVariable Long id) {
        return service.violation(id);
    }

    @PostMapping("/violations/{id}/ack")
    public HosDto.Violation ack(@PathVariable Long id, @RequestBody(required = false) HosDto.AckInput input) {
        return service.ack(id, input);
    }

    @DeleteMapping("/violations/{id}/ack")
    public HosDto.Violation unack(@PathVariable Long id) {
        return service.unack(id);
    }
}

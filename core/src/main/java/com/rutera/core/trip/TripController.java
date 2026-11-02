package com.rutera.core.trip;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Trips of the signed-in user's firma.
 * <pre>
 * GET    /app/trips?status&vehicleId&driverId&from&to&q&page&size -> {items: Trip[], total}  (trip.trip/view)
 * GET    /app/trips/{id}                   -> Trip | 404                                  (trip.trip/view)
 * POST   /app/trips                        {input} -> 201 Trip                            (trip.trip/manage)
 * PUT    /app/trips/{id}                   {input} -> Trip                                (trip.trip/manage)
 * DELETE /app/trips/{id}                   -> 204; 409 unless PLANNED or CANCELLED        (trip.trip/manage)
 * PATCH  /app/trips/{id}/stops/{stopId}    {status, actualAt?} -> TripStop                (trip.trip/manage)
 * </pre>
 */
@RestController
@RequestMapping("/app/trips")
public class TripController {

    private final TripService service;

    public TripController(TripService service) {
        this.service = service;
    }

    @GetMapping
    public TripDto.Page list(@RequestParam(required = false) String status,
                             @RequestParam(required = false) Long vehicleId,
                             @RequestParam(required = false) Long driverId,
                             @RequestParam(required = false) LocalDate from,
                             @RequestParam(required = false) LocalDate to,
                             @RequestParam(required = false) String q,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "50") int size) {
        return service.list(status, vehicleId, driverId, from, to, q, page, size);
    }

    @GetMapping("/{id}")
    public TripDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TripDto create(@Valid @RequestBody TripDto.Input input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    public TripDto update(@PathVariable Long id, @Valid @RequestBody TripDto.Input input) {
        return service.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PatchMapping("/{id}/stops/{stopId}")
    public TripDto.Stop patchStop(@PathVariable Long id, @PathVariable Long stopId,
                                  @RequestBody TripDto.StopStatusInput input) {
        return service.patchStop(id, stopId, input);
    }
}

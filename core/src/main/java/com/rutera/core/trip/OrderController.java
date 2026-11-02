package com.rutera.core.trip;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
 * Customer orders of the signed-in user's firma.
 * <pre>
 * GET    /app/orders?status&customerId&q&from&to&page&size -> {items: Order[], total}   (trip.order/view)
 * GET    /app/orders/{id}             -> Order | 404                                  (trip.order/view)
 * POST   /app/orders                  {input} -> 201 Order                            (trip.order/manage)
 * PUT    /app/orders/{id}             {input} -> Order                                (trip.order/manage)
 * DELETE /app/orders/{id}             -> 204; 409 when on a trip or invoiced          (trip.order/manage)
 * POST   /app/orders/{id}/trip        {vehicleId, ...} -> 201 Trip   (trip.order/manage + trip.trip/manage)
 * </pre>
 */
@RestController
@RequestMapping("/app/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping
    public OrderDto.Page list(@RequestParam(required = false) String status,
                              @RequestParam(required = false) Long customerId,
                              @RequestParam(required = false) String q,
                              @RequestParam(required = false) LocalDate from,
                              @RequestParam(required = false) LocalDate to,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "50") int size) {
        return service.list(status, customerId, q, from, to, page, size);
    }

    @GetMapping("/{id}")
    public OrderDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDto create(@Valid @RequestBody OrderDto.Input input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    public OrderDto update(@PathVariable Long id, @Valid @RequestBody OrderDto.Input input) {
        return service.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/{id}/trip")
    @ResponseStatus(HttpStatus.CREATED)
    public TripDto createTrip(@PathVariable Long id, @Valid @RequestBody OrderDto.TripRequest request) {
        return service.createTrip(id, request);
    }
}

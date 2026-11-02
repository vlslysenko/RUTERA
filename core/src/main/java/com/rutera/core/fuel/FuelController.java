package com.rutera.core.fuel;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Fuel transactions and fuel cards.
 * <pre>
 * GET    /app/fuel/transactions?from&to&vehicleId&driverId&cardId&tripId&importId&product&country&unmatched&q&page&size
 *                                        -> {items, total, totals, quantities}          (fuel.transaction/view)
 * GET    /app/fuel/transactions/{id}     -> FuelTransaction                              (fuel.transaction/view)
 * POST   /app/fuel/transactions          {input} -> 201 FuelTransaction (entered by hand) (fuel.import/manage)
 * PUT    /app/fuel/transactions/{id}     {input} -> FuelTransaction; 409 for imported    (fuel.import/manage)
 * PATCH  /app/fuel/transactions/{id}     {vehicleId, driverId, tripId, notes} -> FuelTransaction (fuel.import/manage)
 * DELETE /app/fuel/transactions/{id}     -> 204                                          (fuel.import/manage)
 * GET    /app/fuel/cards                 -> FuelCard[]                                   (fuel.transaction/view)
 * POST   /app/fuel/cards                 {input} -> 201 FuelCard                         (fuel.import/manage)
 * PUT    /app/fuel/cards/{id}            {input} -> FuelCard                             (fuel.import/manage)
 * DELETE /app/fuel/cards/{id}            -> 204, its transactions stay without a card     (fuel.import/manage)
 * </pre>
 */
@RestController
public class FuelController {

    private final FuelService service;

    public FuelController(FuelService service) {
        this.service = service;
    }

    @GetMapping("/app/fuel/transactions")
    public FuelDto.Page list(@RequestParam(required = false) LocalDate from,
                             @RequestParam(required = false) LocalDate to,
                             @RequestParam(required = false) Long vehicleId,
                             @RequestParam(required = false) Long driverId,
                             @RequestParam(required = false) Long cardId,
                             @RequestParam(required = false) Long tripId,
                             @RequestParam(required = false) Long importId,
                             @RequestParam(required = false) String product,
                             @RequestParam(required = false) String country,
                             @RequestParam(defaultValue = "false") boolean unmatched,
                             @RequestParam(required = false) String q,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "100") int size) {
        return service.list(from, to, vehicleId, driverId, cardId, tripId, importId, product, country, unmatched, q,
                page, size);
    }

    @GetMapping("/app/fuel/transactions/{id}")
    public FuelDto.Transaction get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/app/fuel/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public FuelDto.Transaction create(@Valid @RequestBody FuelDto.TransactionInput input) {
        return service.create(input);
    }

    @PutMapping("/app/fuel/transactions/{id}")
    public FuelDto.Transaction update(@PathVariable Long id, @Valid @RequestBody FuelDto.TransactionInput input) {
        return service.update(id, input);
    }

    @PatchMapping("/app/fuel/transactions/{id}")
    public FuelDto.Transaction assign(@PathVariable Long id, @Valid @RequestBody FuelDto.AssignInput input) {
        return service.assign(id, input);
    }

    @DeleteMapping("/app/fuel/transactions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/app/fuel/cards")
    public List<FuelDto.Card> cards() {
        return service.cards();
    }

    @PostMapping("/app/fuel/cards")
    @ResponseStatus(HttpStatus.CREATED)
    public FuelDto.Card createCard(@Valid @RequestBody FuelDto.CardInput input) {
        return service.createCard(input);
    }

    @PutMapping("/app/fuel/cards/{id}")
    public FuelDto.Card updateCard(@PathVariable Long id, @Valid @RequestBody FuelDto.CardInput input) {
        return service.updateCard(id, input);
    }

    @DeleteMapping("/app/fuel/cards/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCard(@PathVariable Long id) {
        service.deleteCard(id);
    }
}

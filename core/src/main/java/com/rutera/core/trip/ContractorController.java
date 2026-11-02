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

import java.util.List;

/**
 * Contractors of the signed-in user's firma.
 * <pre>
 * GET    /app/contractors?q=&role=CUSTOMER|CARRIER -> Contractor[]     (trip.order/view or trip.contractor/manage)
 * GET    /app/contractors/{id}             -> Contractor | 404      (trip.order/view or trip.contractor/manage)
 * POST   /app/contractors                  {input} -> 201 Contractor (trip.contractor/manage)
 * PUT    /app/contractors/{id}             {input} -> Contractor    (trip.contractor/manage)
 * DELETE /app/contractors/{id}             -> 204 (soft delete)     (trip.contractor/manage)
 * </pre>
 */
@RestController
@RequestMapping("/app/contractors")
public class ContractorController {

    private final ContractorService service;

    public ContractorController(ContractorService service) {
        this.service = service;
    }

    @GetMapping
    public List<ContractorDto> list(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) String role) {
        return service.list(q, role);
    }

    @GetMapping("/{id}")
    public ContractorDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContractorDto create(@Valid @RequestBody ContractorDto.Input input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    public ContractorDto update(@PathVariable Long id, @Valid @RequestBody ContractorDto.Input input) {
        return service.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}

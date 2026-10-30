package com.rutera.core.driver;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Drivers of the signed-in user's firma, with their tachograph card and documents.
 * <pre>
 * GET    /app/drivers                           -> Driver[]                    (driver.driver/view)
 * GET    /app/drivers/{id}                      -> Driver | 404                (driver.driver/view)
 * POST   /app/drivers                           {DriverInput} -> 201 Driver    (driver.driver/manage)
 * PUT    /app/drivers/{id}                      {DriverInput} -> Driver        (driver.driver/manage)
 * DELETE /app/drivers/{id}                      -> 204 (soft delete)           (driver.driver/manage)
 * GET    /app/drivers/{id}/documents            -> DriverDocument[]            (driver.driver/view)
 * POST   /app/drivers/{id}/documents            {input} -> 201 DriverDocument  (driver.driver/manage)
 * PUT    /app/drivers/{id}/documents/{docId}    {input} -> DriverDocument      (driver.driver/manage)
 * DELETE /app/drivers/{id}/documents/{docId}    -> 204                         (driver.driver/manage)
 * </pre>
 * 422 {@code error.validation} with per-field codes, e.g. {@code cardNumber: driver.card.number_taken}.
 */
@RestController
@RequestMapping("/app/drivers")
public class DriverController {

    private final DriverService service;

    public DriverController(DriverService service) {
        this.service = service;
    }

    @GetMapping
    public List<DriverDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public DriverDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DriverDto create(@Valid @RequestBody DriverInput input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    public DriverDto update(@PathVariable Long id, @Valid @RequestBody DriverInput input) {
        return service.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/{id}/documents")
    public List<DriverDocumentDto> documents(@PathVariable Long id) {
        return service.documents(id);
    }

    @PostMapping("/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public DriverDocumentDto createDocument(@PathVariable Long id, @Valid @RequestBody DriverDocumentInput input) {
        return service.createDocument(id, input);
    }

    @PutMapping("/{id}/documents/{documentId}")
    public DriverDocumentDto updateDocument(@PathVariable Long id, @PathVariable Long documentId,
                                            @Valid @RequestBody DriverDocumentInput input) {
        return service.updateDocument(id, documentId, input);
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable Long id, @PathVariable Long documentId) {
        service.deleteDocument(id, documentId);
    }
}

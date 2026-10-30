package com.rutera.core.fleet;

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
 * Fleet of the signed-in user's firma: vehicles, trailers and their documents.
 * <pre>
 * GET    /app/fleet/vehicles                            -> Vehicle[]                 (fleet.vehicle/view)
 * GET    /app/fleet/vehicles/{id}                       -> Vehicle | 404             (fleet.vehicle/view)
 * POST   /app/fleet/vehicles                            {VehicleInput} -> 201 Vehicle (fleet.vehicle/manage)
 * PUT    /app/fleet/vehicles/{id}                       {VehicleInput} -> Vehicle   (fleet.vehicle/manage)
 * DELETE /app/fleet/vehicles/{id}                       -> 204 (soft delete)         (fleet.vehicle/manage)
 * GET    /app/fleet/vehicles/{id}/documents             -> VehicleDocument[]         (fleet.vehicle/view)
 * POST   /app/fleet/vehicles/{id}/documents             {input} -> 201 VehicleDocument (fleet.document/manage)
 * PUT    /app/fleet/vehicles/{id}/documents/{docId}     {input} -> VehicleDocument (fleet.document/manage)
 * DELETE /app/fleet/vehicles/{id}/documents/{docId}     -> 204                       (fleet.document/manage)
 * </pre>
 * 422 {@code error.validation} with per-field codes, e.g. {@code plate: fleet.vehicle.plate_taken}.
 */
@RestController
@RequestMapping("/app/fleet/vehicles")
public class VehicleController {

    private final VehicleService service;

    public VehicleController(VehicleService service) {
        this.service = service;
    }

    @GetMapping
    public List<VehicleDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public VehicleDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleDto create(@Valid @RequestBody VehicleInput input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    public VehicleDto update(@PathVariable Long id, @Valid @RequestBody VehicleInput input) {
        return service.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/{id}/documents")
    public List<VehicleDocumentDto> documents(@PathVariable Long id) {
        return service.documents(id);
    }

    @PostMapping("/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleDocumentDto createDocument(@PathVariable Long id, @Valid @RequestBody VehicleDocumentInput input) {
        return service.createDocument(id, input);
    }

    @PutMapping("/{id}/documents/{documentId}")
    public VehicleDocumentDto updateDocument(@PathVariable Long id, @PathVariable Long documentId,
                                             @Valid @RequestBody VehicleDocumentInput input) {
        return service.updateDocument(id, documentId, input);
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable Long id, @PathVariable Long documentId) {
        service.deleteDocument(id, documentId);
    }
}

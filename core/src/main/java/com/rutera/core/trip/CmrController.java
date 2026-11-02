package com.rutera.core.trip;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * CMR consignment notes of the signed-in user's firma.
 * <pre>
 * GET    /app/cmr?status&orderId&tripId&q&from&to&page&size -> {items: Cmr[], total}  (trip.order/view or trip.cmr/manage)
 * GET    /app/cmr/{id}               -> Cmr                                             (same)
 * GET    /app/cmr/{id}/pdf?copies    -> application/pdf (draft: watermarked; issued: the stored PDF)  (same)
 * POST   /app/cmr                    {input} -> 201 Cmr                                 (trip.cmr/manage)
 * PUT    /app/cmr/{id}               {input} -> Cmr; 409 unless DRAFT                   (trip.cmr/manage)
 * DELETE /app/cmr/{id}               -> 204; 409 unless DRAFT                           (trip.cmr/manage)
 * POST   /app/cmr/from-order/{orderId} -> 201 Cmr (prefilled draft)                     (trip.cmr/manage)
 * POST   /app/cmr/from-trip/{tripId} -> 201 Cmr[] (one per order of the trip)           (trip.cmr/manage)
 * POST   /app/cmr/{id}/issue         -> Cmr (ISSUED, PDF stored); 422 when boxes are missing (trip.cmr/manage)
 * POST   /app/cmr/{id}/cancel        -> Cmr (CANCELLED)                                 (trip.cmr/manage)
 * </pre>
 */
@RestController
@RequestMapping({"/app/cmr", "/api/v1/cmr"})
public class CmrController {

    private final CmrService service;

    public CmrController(CmrService service) {
        this.service = service;
    }

    @GetMapping
    public CmrDto.Page list(@RequestParam(required = false) String status,
                            @RequestParam(required = false) Long orderId,
                            @RequestParam(required = false) Long tripId,
                            @RequestParam(required = false) String q,
                            @RequestParam(required = false) LocalDate from,
                            @RequestParam(required = false) LocalDate to,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "50") int size) {
        return service.list(status, orderId, tripId, q, from, to, page, size);
    }

    @GetMapping("/{id}")
    public CmrDto.Cmr get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id, @RequestParam(defaultValue = "1") int copies) {
        CmrService.Pdf pdf = service.pdf(id, copies);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(pdf.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(pdf.bytes());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CmrDto.Cmr create(@Valid @RequestBody CmrDto.Input input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    public CmrDto.Cmr update(@PathVariable Long id, @Valid @RequestBody CmrDto.Input input) {
        return service.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/from-order/{orderId}")
    @ResponseStatus(HttpStatus.CREATED)
    public CmrDto.Cmr fromOrder(@PathVariable Long orderId) {
        return service.fromOrder(orderId);
    }

    @PostMapping("/from-trip/{tripId}")
    @ResponseStatus(HttpStatus.CREATED)
    public List<CmrDto.Cmr> fromTrip(@PathVariable Long tripId) {
        return service.fromTrip(tripId);
    }

    @PostMapping("/{id}/issue")
    public CmrDto.Cmr issue(@PathVariable Long id) {
        return service.issue(id);
    }

    @PostMapping("/{id}/cancel")
    public CmrDto.Cmr cancel(@PathVariable Long id) {
        return service.cancel(id);
    }
}

package com.rutera.core.explain;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Driver explanations of tachograph infringements (see {@link ExplanationService}).
 * <pre>
 * GET  /app/explanations?status=&driverId=&from=&to=&page=&size=  -> {items, total}   (tacho.hos/view; a driver: own)
 * GET  /app/explanations/{id}            -> Explanation                              (same)
 * GET  /app/explanations/{id}/pdf        -> application/pdf (unsigned: a draft preview) (same)
 * POST /app/explanations                 {violationId} | {driverId, vehicleId?, ruleCode, startedAt, endedAt?,
 *                                         exceededMin?} -> 201 Explanation          (tacho.violation/ack)
 * POST /app/explanations/{id}/remind     -> Explanation (push again)                 (tacho.violation/ack)
 * POST /app/explanations/{id}/cancel     -> Explanation                              (tacho.violation/ack)
 * POST /app/explanations/{id}/sign       {reason, reasonText?, place?, signature} -> Explanation (the driver)
 * </pre>
 */
@RestController
@RequestMapping("/app/explanations")
public class ExplanationController {

    private final ExplanationService service;

    public ExplanationController(ExplanationService service) {
        this.service = service;
    }

    @GetMapping
    public ExplanationDto.Page list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) Long driverId,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                    LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                    LocalDate to,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        return service.list(status, driverId, from, to, page, size);
    }

    @GetMapping("/{id}")
    public ExplanationDto.Explanation get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        ExplanationService.Pdf pdf = service.pdf(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(pdf.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(pdf.bytes());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExplanationDto.Explanation create(@RequestBody ExplanationDto.CreateInput input) {
        return service.create(input);
    }

    @PostMapping("/{id}/remind")
    public ExplanationDto.Explanation remind(@PathVariable Long id) {
        return service.remind(id);
    }

    @PostMapping("/{id}/cancel")
    public ExplanationDto.Explanation cancel(@PathVariable Long id) {
        return service.cancel(id);
    }

    @PostMapping("/{id}/sign")
    public ExplanationDto.Explanation sign(@PathVariable Long id, @Valid @RequestBody ExplanationDto.SignInput input) {
        return service.sign(id, input);
    }
}

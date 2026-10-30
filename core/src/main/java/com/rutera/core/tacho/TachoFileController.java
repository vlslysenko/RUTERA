package com.rutera.core.tacho;

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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Tachograph downloads (docs/TACHO_IMPORT_API.md, step A). Privileges {@code tacho.file/upload} and
 * {@code tacho.hos/view}.
 * <pre>
 * POST   /app/tacho/files                   multipart "files" (1..50) -> TachoImportResult[]      (upload)
 * GET    /app/tacho/files?kind&driverId&vehicleId&unassigned&from&to&page&size -> {items, total} (view or upload)
 * GET    /app/tacho/files/{id}              -> TachoFileDetail                             (view or upload)
 * GET    /app/tacho/files/{id}/content      -> the original bytes                          (upload)
 * PUT    /app/tacho/files/{id}/assignment   {driverId} | {vehicleId} -> TachoFile          (upload)
 * POST   /app/tacho/files/{id}/driver       -> 201 {driver, file}             (upload + driver.driver/manage)
 * POST   /app/tacho/files/{id}/vehicle      -> 201 {vehicle, file}            (upload + fleet.vehicle/manage)
 * DELETE /app/tacho/files/{id}              -> 204                                         (upload)
 * </pre>
 */
@RestController
@RequestMapping("/app/tacho/files")
public class TachoFileController {

    private final TachoImportService service;

    public TachoFileController(TachoImportService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<TachoFileDto.ImportResult> upload(@RequestPart(value = "files", required = false)
                                                  List<MultipartFile> files) {
        List<TachoImportService.Upload> uploads = new ArrayList<>();
        if (files != null) {
            for (MultipartFile part : files) {
                uploads.add(new TachoImportService.Upload(part.getOriginalFilename(), part.getSize(), bytesOf(part)));
            }
        }
        return service.upload(uploads);
    }

    @GetMapping
    public TachoFileDto.Page list(@RequestParam(required = false) String kind,
                                  @RequestParam(required = false) Long driverId,
                                  @RequestParam(required = false) Long vehicleId,
                                  @RequestParam(defaultValue = "false") boolean unassigned,
                                  @RequestParam(required = false) LocalDate from,
                                  @RequestParam(required = false) LocalDate to,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        return service.list(kind, driverId, vehicleId, unassigned, from, to, page, size);
    }

    @GetMapping("/{id}")
    public TachoFileDto.Detail get(@PathVariable Long id) {
        return service.detail(id);
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long id) {
        TachoImportService.Content content = service.content(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.originalName(), StandardCharsets.UTF_8).build().toString())
                .body(content.bytes());
    }

    @PutMapping("/{id}/assignment")
    public TachoFileDto assign(@PathVariable Long id, @RequestBody TachoFileDto.AssignmentInput input) {
        return service.assign(id, input);
    }

    @PostMapping("/{id}/driver")
    @ResponseStatus(HttpStatus.CREATED)
    public TachoFileDto.CreatedDriver createDriver(@PathVariable Long id) {
        return service.createDriver(id);
    }

    @PostMapping("/{id}/vehicle")
    @ResponseStatus(HttpStatus.CREATED)
    public TachoFileDto.CreatedVehicle createVehicle(@PathVariable Long id) {
        return service.createVehicle(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** Parts over the limit are not read at all; the service rejects them as too large. */
    private static byte[] bytesOf(MultipartFile part) {
        if (part.getSize() > TachoImportService.MAX_FILE_BYTES) {
            return null;
        }
        try {
            return part.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

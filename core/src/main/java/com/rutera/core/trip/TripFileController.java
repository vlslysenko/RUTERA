package com.rutera.core.trip;

import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Photos and documents of a trip.
 * <pre>
 * GET    /app/trips/{id}/files                     -> TripFile[] (trip.trip/view, or the driver on the crew)
 * POST   /app/trips/{id}/files   multipart file, kind?, note?, stopId? -> 201 TripFile
 *                                (trip.trip/manage, trip.cmr/upload, or the driver on the crew while the trip is open)
 * GET    /app/trips/{id}/files/{fileId}/content    -> the file inline
 * DELETE /app/trips/{id}/files/{fileId}            -> 204 (trip.trip/manage, or the driver's own upload, trip open)
 * </pre>
 */
@RestController
public class TripFileController {

    private final TripFileService service;

    public TripFileController(TripFileService service) {
        this.service = service;
    }

    @GetMapping({"/app/trips/{id}/files", "/api/v1/trips/{id}/files"})
    public List<TripFileService.FileDto> list(@PathVariable Long id) {
        return service.list(id);
    }

    @PostMapping(value = {"/app/trips/{id}/files", "/api/v1/trips/{id}/files"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TripFileService.FileDto upload(@PathVariable Long id,
                                          @RequestPart(value = "file", required = false) MultipartFile file,
                                          @RequestParam(required = false) String kind,
                                          @RequestParam(required = false) String note,
                                          @RequestParam(required = false) Long stopId) {
        byte[] bytes;
        try {
            bytes = file == null ? null : file.getBytes();
        } catch (IOException e) {
            throw ApiException.validation(Map.of("file", ErrorCodes.FIELD_REQUIRED));
        }
        return service.upload(id, file == null ? null : file.getOriginalFilename(), bytes, kind, note, stopId);
    }

    @GetMapping({"/app/trips/{id}/files/{fileId}/content", "/api/v1/trips/{id}/files/{fileId}/content"})
    public ResponseEntity<byte[]> content(@PathVariable Long id, @PathVariable Long fileId) {
        TripFileService.Content c = service.content(id, fileId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(c.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(c.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(c.bytes());
    }

    @DeleteMapping({"/app/trips/{id}/files/{fileId}", "/api/v1/trips/{id}/files/{fileId}"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @PathVariable Long fileId) {
        service.delete(id, fileId);
    }
}

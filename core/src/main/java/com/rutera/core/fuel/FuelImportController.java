package com.rutera.core.fuel;

import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Imports of fuel card exports and the saved column mappings.
 * <pre>
 * POST   /app/fuel/imports/preview  multipart file, provider?, mapping? (JSON), options? (JSON) -> Preview
 * POST   /app/fuel/imports          multipart file, provider?, mapping?, options?, saveMapping? -> 201 FuelImport
 * GET    /app/fuel/imports?page&size -> {items: FuelImport[], total}                    (fuel.transaction/view)
 * GET    /app/fuel/imports/{id}     -> FuelImport                                        (fuel.transaction/view)
 * DELETE /app/fuel/imports/{id}     -> 204, its transactions are removed                 (fuel.import/manage)
 * GET    /app/fuel/mappings/{provider}  -> Mapping                                       (fuel.import/manage)
 * PUT    /app/fuel/mappings/{provider}  {columns, options} -> Mapping                    (fuel.import/manage)
 * </pre>
 */
@RestController
public class FuelImportController {

    private final FuelImportService service;

    public FuelImportController(FuelImportService service) {
        this.service = service;
    }

    @PostMapping(value = {"/app/fuel/imports/preview", "/api/v1/fuel/imports/preview"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FuelDto.Preview preview(@RequestPart(value = "file", required = false) MultipartFile file,
                                   @RequestParam(required = false) String provider,
                                   @RequestParam(required = false) String mapping,
                                   @RequestParam(required = false) String options) {
        return service.preview(name(file), bytes(file), provider, mapping, options);
    }

    @PostMapping(value = {"/app/fuel/imports", "/api/v1/fuel/imports"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FuelDto.Import importFile(@RequestPart(value = "file", required = false) MultipartFile file,
                                     @RequestParam(required = false) String provider,
                                     @RequestParam(required = false) String mapping,
                                     @RequestParam(required = false) String options,
                                     @RequestParam(defaultValue = "true") boolean saveMapping) {
        return service.importFile(name(file), bytes(file), provider, mapping, options, saveMapping);
    }

    @GetMapping({"/app/fuel/imports", "/api/v1/fuel/imports"})
    public FuelDto.ImportPage list(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        return service.list(page, size);
    }

    @GetMapping({"/app/fuel/imports/{id}", "/api/v1/fuel/imports/{id}"})
    public FuelDto.Import get(@PathVariable Long id) {
        return service.get(id);
    }

    @DeleteMapping({"/app/fuel/imports/{id}", "/api/v1/fuel/imports/{id}"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping({"/app/fuel/mappings/{provider}", "/api/v1/fuel/mappings/{provider}"})
    public FuelDto.Mapping mapping(@PathVariable String provider) {
        return service.mapping(provider);
    }

    @PutMapping({"/app/fuel/mappings/{provider}", "/api/v1/fuel/mappings/{provider}"})
    public FuelDto.Mapping updateMapping(@PathVariable String provider,
                                         @Valid @RequestBody FuelDto.MappingInput input) {
        return service.updateMapping(provider, input);
    }

    private static String name(MultipartFile file) {
        return file == null ? null : file.getOriginalFilename();
    }

    private static byte[] bytes(MultipartFile file) {
        if (file == null) {
            return null;
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.validation(Map.of("file", ErrorCodes.FUEL_UNREADABLE));
        }
    }
}

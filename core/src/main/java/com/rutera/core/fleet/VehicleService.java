package com.rutera.core.fleet;

import com.rutera.common.domain.Driver;
import com.rutera.common.domain.Vehicle;
import com.rutera.common.domain.VehicleDocument;
import com.rutera.common.repository.DriverRepository;
import com.rutera.common.repository.VehicleDocumentRepository;
import com.rutera.common.repository.VehicleRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Vehicles, trailers and their documents ({@code /app/fleet/vehicles}). The repositories are tenant-filtered;
 * every load by id also checks the firma of the row (second line of defence) and answers 404 otherwise.
 * <p>
 * Plates are stored trimmed, upper-cased and with single spaces; two plates are the same when they match without
 * spaces and hyphens ({@code WX 12345} = {@code wx-12345}). Deleting a vehicle is a soft delete that frees the
 * plate and detaches the trailer from tractors that use it by default. Every change goes to the audit log.
 */
@Service
public class VehicleService {

    public static final String MODULE = "fleet";

    private static final Pattern PLATE_SHAPE = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} -]*");
    private static final Pattern VIN_SHAPE = Pattern.compile("[A-Z0-9]{1,17}");
    private static final Pattern CURRENCY_SHAPE = Pattern.compile("[A-Z]{3}");
    /** Fields of {@link VehicleDto} that are not compared when listing the changes of an update. */
    private static final Set<String> NOT_AUDITED = Set.of("createdAt", "updatedAt", "nextDocument");

    private final VehicleRepository vehicles;
    private final VehicleDocumentRepository documents;
    private final DriverRepository drivers;
    private final AuditService audit;

    public VehicleService(VehicleRepository vehicles, VehicleDocumentRepository documents, DriverRepository drivers,
                          AuditService audit) {
        this.vehicles = vehicles;
        this.documents = documents;
        this.drivers = drivers;
        this.audit = audit;
    }

    // --- vehicles ----------------------------------------------------------------------------------------

    /** All non-deleted vehicles of the firma, sorted by plate. */
    @RequiresPrivilege(Privileges.FLEET_VEHICLE_VIEW)
    @Transactional(readOnly = true)
    public List<VehicleDto> list() {
        actorFirmaId();
        List<Vehicle> all = vehicles.findByDeletedFalseOrderByPlateAsc();
        Map<Long, Vehicle> byId = all.stream().collect(Collectors.toMap(Vehicle::getId, Function.identity()));
        Map<Long, VehicleDocument> next = new HashMap<>();
        if (!all.isEmpty()) {
            // Sorted by valid_to: the first document seen per vehicle is its nearest deadline.
            for (VehicleDocument d : documents.findByVehicleIdInAndValidToNotNullOrderByValidToAscIdAsc(byId.keySet())) {
                next.putIfAbsent(d.getVehicleId(), d);
            }
        }
        List<Long> driverIds = all.stream().map(Vehicle::getDefaultDriverId).filter(Objects::nonNull).toList();
        Map<Long, Driver> driverOf = driverIds.isEmpty() ? Map.of() : drivers.findByIdInAndDeletedFalse(driverIds)
                .stream().collect(Collectors.toMap(Driver::getId, Function.identity()));
        return all.stream()
                .map(v -> VehicleDto.of(v, v.getDefaultTrailerId() == null ? null : byId.get(v.getDefaultTrailerId()),
                        v.getDefaultDriverId() == null ? null : driverOf.get(v.getDefaultDriverId()),
                        next.get(v.getId())))
                .toList();
    }

    @RequiresPrivilege(Privileges.FLEET_VEHICLE_VIEW)
    @Transactional(readOnly = true)
    public VehicleDto get(Long id) {
        return toDto(load(id));
    }

    @RequiresPrivilege(Privileges.FLEET_VEHICLE_MANAGE)
    @Transactional
    public VehicleDto create(VehicleInput input) {
        Long firmaId = actorFirmaId();
        Vehicle vehicle = new Vehicle();
        vehicle.setFirmaId(firmaId);
        apply(vehicle, input);
        vehicle = saveCheckingPlate(vehicle);
        audit.record(AuditEvent.of(MODULE, "vehicle_created", "Added vehicle " + vehicle.getPlate())
                .entity("vehicle", vehicle.getId())
                .detail("plate", vehicle.getPlate())
                .detail("kind", vehicle.getKind().name()));
        return toDto(vehicle);
    }

    /** Full replacement of the editable fields. */
    @RequiresPrivilege(Privileges.FLEET_VEHICLE_MANAGE)
    @Transactional
    public VehicleDto update(Long id, VehicleInput input) {
        Vehicle vehicle = load(id);
        VehicleDto before = toDto(vehicle);
        boolean wasTrailer = vehicle.getKind().isTrailer();
        apply(vehicle, input);
        if (wasTrailer && !vehicle.getKind().isTrailer()) {
            vehicles.clearDefaultTrailer(vehicle.getId());
        }
        vehicle = saveCheckingPlate(vehicle);
        VehicleDto after = toDto(vehicle);
        List<String> changed = changedFields(before, after);
        if (!changed.isEmpty()) {
            audit.record(AuditEvent.of(MODULE, "vehicle_updated", "Updated vehicle " + vehicle.getPlate())
                    .entity("vehicle", vehicle.getId())
                    .detail("fields", changed));
        }
        return after;
    }

    /** Soft delete: the row stays for history (trips, fuel, tachograph), the plate becomes free. */
    @RequiresPrivilege(Privileges.FLEET_VEHICLE_MANAGE)
    @Transactional
    public void delete(Long id) {
        Vehicle vehicle = load(id);
        if (vehicle.getKind().isTrailer()) {
            vehicles.clearDefaultTrailer(vehicle.getId());
        }
        vehicle.setDeleted(true);
        vehicle.setDefaultTrailerId(null);
        vehicles.save(vehicle);
        audit.record(AuditEvent.of(MODULE, "vehicle_deleted", "Deleted vehicle " + vehicle.getPlate())
                .entity("vehicle", vehicle.getId())
                .detail("plate", vehicle.getPlate()));
    }

    // --- documents ---------------------------------------------------------------------------------------

    /** Documents of a vehicle: nearest deadline first, documents without a deadline last. */
    @RequiresPrivilege(Privileges.FLEET_VEHICLE_VIEW)
    @Transactional(readOnly = true)
    public List<VehicleDocumentDto> documents(Long vehicleId) {
        Vehicle vehicle = load(vehicleId);
        return documents.findByVehicleId(vehicle.getId()).stream()
                .sorted(Comparator.comparing(VehicleDocument::getValidTo,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(VehicleDocument::getId))
                .map(VehicleDocumentDto::of)
                .toList();
    }

    @RequiresPrivilege(Privileges.FLEET_DOCUMENT_MANAGE)
    @Transactional
    public VehicleDocumentDto createDocument(Long vehicleId, VehicleDocumentInput input) {
        Vehicle vehicle = load(vehicleId);
        VehicleDocument document = new VehicleDocument();
        document.setFirmaId(vehicle.getFirmaId());
        document.setVehicleId(vehicle.getId());
        apply(document, input);
        document = documents.save(document);
        audit.record(documentEvent("document_created", "Added document", vehicle, document));
        return VehicleDocumentDto.of(document);
    }

    @RequiresPrivilege(Privileges.FLEET_DOCUMENT_MANAGE)
    @Transactional
    public VehicleDocumentDto updateDocument(Long vehicleId, Long documentId, VehicleDocumentInput input) {
        Vehicle vehicle = load(vehicleId);
        VehicleDocument document = loadDocument(vehicle, documentId);
        apply(document, input);
        document = documents.save(document);
        audit.record(documentEvent("document_updated", "Updated document", vehicle, document));
        return VehicleDocumentDto.of(document);
    }

    @RequiresPrivilege(Privileges.FLEET_DOCUMENT_MANAGE)
    @Transactional
    public void deleteDocument(Long vehicleId, Long documentId) {
        Vehicle vehicle = load(vehicleId);
        VehicleDocument document = loadDocument(vehicle, documentId);
        documents.delete(document);
        audit.record(documentEvent("document_deleted", "Deleted document", vehicle, document));
    }

    // --- helpers -----------------------------------------------------------------------------------------

    private Vehicle load(Long id) {
        Long firmaId = actorFirmaId();
        return vehicles.findByIdAndDeletedFalse(id)
                .filter(v -> firmaId.equals(v.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private VehicleDocument loadDocument(Vehicle vehicle, Long documentId) {
        return documents.findByIdAndVehicleId(documentId, vehicle.getId())
                .filter(d -> vehicle.getFirmaId().equals(d.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    /** The fleet is firma-scoped; the service superuser has no firma and therefore no fleet. */
    private static Long actorFirmaId() {
        return InputFields.requireFirmaId();
    }

    private VehicleDto toDto(Vehicle vehicle) {
        Vehicle trailer = vehicle.getDefaultTrailerId() == null ? null
                : vehicles.findByIdAndDeletedFalse(vehicle.getDefaultTrailerId()).orElse(null);
        Driver driver = vehicle.getDefaultDriverId() == null ? null
                : drivers.findByIdAndDeletedFalse(vehicle.getDefaultDriverId()).orElse(null);
        VehicleDocument next = documents.findByVehicleIdInAndValidToNotNullOrderByValidToAscIdAsc(
                List.of(vehicle.getId())).stream().findFirst().orElse(null);
        return VehicleDto.of(vehicle, trailer, driver, next);
    }

    /** Validates the input and copies it onto the vehicle; 422 with per-field codes on the first problem per field. */
    private void apply(Vehicle vehicle, VehicleInput in) {
        Map<String, String> errors = new LinkedHashMap<>();
        Vehicle.Kind kind = parse(Vehicle.Kind.class, "kind", in.kind(), true, errors);
        String plate = normalizePlate(in.plate(), errors);
        String vin = normalizeVin(in.vin(), errors);
        Vehicle.EuroClass euroClass = parse(Vehicle.EuroClass.class, "euroClass", in.euroClass(), false, errors);
        Vehicle.FuelType fuelType = parse(Vehicle.FuelType.class, "fuelType", in.fuelType(), false, errors);
        Vehicle.TachoGen tachoGen = parse(Vehicle.TachoGen.class, "tachoGen", in.tachoGen(), false, errors);
        Vehicle.Status status = parse(Vehicle.Status.class, "status", in.status(), false, errors);
        boolean trailer = kind != null && kind.isTrailer();
        Long trailerId = trailer ? null : checkTrailer(vehicle, in.defaultTrailerId(), errors);
        Long driverId = trailer ? null : checkDriver(in.defaultDriverId(), errors);
        if (plate != null && plateTaken(plate, vehicle.getId())) {
            errors.put("plate", ErrorCodes.VEHICLE_PLATE_TAKEN);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }

        vehicle.setKind(kind);
        vehicle.setPlate(plate);
        vehicle.setVin(vin);
        vehicle.setMake(blankToNull(in.make()));
        vehicle.setModel(blankToNull(in.model()));
        vehicle.setEuroClass(euroClass);
        vehicle.setAxles(in.axles());
        vehicle.setGrossWeightKg(in.grossWeightKg());
        vehicle.setHeightMm(in.heightMm());
        vehicle.setStatus(status == null ? Vehicle.Status.ACTIVE : status);
        vehicle.setTachoGen(trailer || tachoGen == null ? Vehicle.TachoGen.NONE : tachoGen);
        vehicle.setFuelType(trailer ? null : fuelType);
        vehicle.setAvgL100km(trailer ? null : scale2(in.avgL100km()));
        vehicle.setOdometerKm(trailer ? null : in.odometerKm());
        vehicle.setDefaultDriverId(driverId);
        vehicle.setDefaultTrailerId(trailerId);
    }

    private void apply(VehicleDocument document, VehicleDocumentInput in) {
        Map<String, String> errors = new LinkedHashMap<>();
        VehicleDocument.Kind kind = parse(VehicleDocument.Kind.class, "kind", in.kind(), true, errors);
        if (in.validFrom() != null && in.validTo() != null && in.validTo().isBefore(in.validFrom())) {
            errors.put("validTo", ErrorCodes.FIELD_INVALID_VALUE);
        }
        String currency = blankToNull(in.currency());
        if (currency != null) {
            currency = currency.toUpperCase(Locale.ROOT);
            if (!CURRENCY_SHAPE.matcher(currency).matches()) {
                errors.put("currency", ErrorCodes.FIELD_INVALID_FORMAT);
            }
        } else if (in.cost() != null) {
            errors.put("currency", ErrorCodes.FIELD_REQUIRED);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        document.setKind(kind);
        document.setNumber(blankToNull(in.number()));
        document.setValidFrom(in.validFrom());
        document.setValidTo(in.validTo());
        document.setCost(scale2(in.cost()));
        document.setCurrency(in.cost() == null ? null : currency);
        document.setRemindDays(in.remindDays() == null ? VehicleDocument.DEFAULT_REMIND_DAYS : in.remindDays());
    }

    /** A default trailer must be a non-deleted trailer of the same firma and not the vehicle itself. */
    private Long checkTrailer(Vehicle vehicle, Long trailerId, Map<String, String> errors) {
        if (trailerId == null) {
            return null;
        }
        boolean ok = !trailerId.equals(vehicle.getId()) && vehicles.findByIdAndDeletedFalse(trailerId)
                .filter(t -> t.getFirmaId().equals(actorFirmaId()) && t.getKind().isTrailer())
                .isPresent();
        if (!ok) {
            errors.put("defaultTrailerId", ErrorCodes.FIELD_INVALID_VALUE);
            return null;
        }
        return trailerId;
    }

    /** A default driver must be a non-deleted driver of the same firma. */
    private Long checkDriver(Long driverId, Map<String, String> errors) {
        if (driverId == null) {
            return null;
        }
        boolean ok = drivers.findByIdAndDeletedFalse(driverId)
                .filter(d -> d.getFirmaId().equals(actorFirmaId()))
                .isPresent();
        if (!ok) {
            errors.put("defaultDriverId", ErrorCodes.FIELD_INVALID_VALUE);
            return null;
        }
        return driverId;
    }

    private boolean plateTaken(String plate, Long ownId) {
        String key = plateKey(plate);
        return vehicles.findByDeletedFalseOrderByPlateAsc().stream()
                .anyMatch(v -> !v.getId().equals(ownId) && plateKey(v.getPlate()).equals(key));
    }

    /** The unique key in MySQL (plate_key) is the last word on duplicates, e.g. two requests at the same time. */
    private Vehicle saveCheckingPlate(Vehicle vehicle) {
        try {
            return vehicles.saveAndFlush(vehicle);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.validation(Map.of("plate", ErrorCodes.VEHICLE_PLATE_TAKEN));
        }
    }

    static String normalizePlate(String raw, Map<String, String> errors) {
        String plate = raw == null ? "" : raw.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (plate.isEmpty()) {
            errors.put("plate", ErrorCodes.FIELD_REQUIRED);
            return null;
        }
        if (!PLATE_SHAPE.matcher(plate).matches()) {
            errors.put("plate", ErrorCodes.FIELD_INVALID_FORMAT);
            return null;
        }
        return plate;
    }

    /** Same rule as the generated column {@code vehicle.plate_key} in V2__fleet_vehicles.sql. */
    public static String plateKey(String plate) {
        return plate.toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "");
    }

    private static String normalizeVin(String raw, Map<String, String> errors) {
        String vin = raw == null ? "" : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (vin.isEmpty()) {
            return null;
        }
        if (!VIN_SHAPE.matcher(vin).matches()) {
            errors.put("vin", ErrorCodes.FIELD_INVALID_FORMAT);
            return null;
        }
        return vin;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String field, String raw, boolean required,
                                               Map<String, String> errors) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                errors.put(field, ErrorCodes.FIELD_REQUIRED);
            }
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.put(field, ErrorCodes.FIELD_INVALID_VALUE);
            return null;
        }
    }

    private static AuditEvent documentEvent(String action, String verb, Vehicle vehicle, VehicleDocument document) {
        return AuditEvent.of(MODULE, action, verb + " " + document.getKind().name() + " of vehicle " + vehicle.getPlate())
                .entity("vehicle_document", document.getId())
                .detail("vehicleId", vehicle.getId())
                .detail("kind", document.getKind().name())
                .detail("validTo", document.getValidTo() == null ? "" : document.getValidTo().toString());
    }

    /** Names of the record components that differ (for the audit entry of an update). */
    private static List<String> changedFields(VehicleDto before, VehicleDto after) {
        List<String> changed = new ArrayList<>();
        for (RecordComponent component : VehicleDto.class.getRecordComponents()) {
            if (NOT_AUDITED.contains(component.getName())) {
                continue;
            }
            try {
                Object a = component.getAccessor().invoke(before);
                Object b = component.getAccessor().invoke(after);
                if (!Objects.equals(a, b)) {
                    changed.add(component.getName());
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return changed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** DECIMAL(x,2) columns: the same scale as read back, so 29.5 and 29.50 compare equal. */
    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}

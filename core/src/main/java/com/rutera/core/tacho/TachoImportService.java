package com.rutera.core.tacho;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Driver;
import com.rutera.common.domain.DriverCard;
import com.rutera.common.domain.StoredFile;
import com.rutera.common.domain.TachoFileRecord;
import com.rutera.common.domain.Vehicle;
import com.rutera.common.i18n.LangService;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.DriverCardRepository;
import com.rutera.common.repository.DriverRepository;
import com.rutera.common.repository.StoredFileRepository;
import com.rutera.common.repository.TachoFileRepository;
import com.rutera.common.repository.VehicleRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.driver.DriverDto;
import com.rutera.core.driver.DriverInput;
import com.rutera.core.driver.DriverService;
import com.rutera.core.fleet.VehicleDto;
import com.rutera.core.fleet.VehicleInput;
import com.rutera.core.fleet.VehicleService;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.storage.FileStorage;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import com.rutera.tacho.TachoFile;
import com.rutera.tacho.TachoFileSummary;
import com.rutera.tacho.TachoFormatException;
import com.rutera.tacho.TachoParser;
import com.rutera.tacho.card.DriverCardFile;
import com.rutera.tacho.card.DriverCardHolder;
import com.rutera.tacho.vu.VuActivityDay;
import com.rutera.tacho.vu.VuCardInsertion;
import com.rutera.tacho.vu.VuFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Import of tachograph downloads, step A of docs/TACHO_IMPORT_API.md: the file, its summary, the driver or vehicle
 * it belongs to, and {@code lastDownloadAt} of the driver card and the vehicle.
 * <p>
 * Every uploaded file is handled in its own transaction, so a bad file does not stop the others; a file whose
 * sha256 is already known in the firma is a duplicate and changes nothing; rejected files are not stored.
 * Matching: a driver card by its card number, otherwise by the driver identification (first 14 characters, a
 * renewed or replaced card, which then updates the driver's card when it is newer); a vehicle unit by VIN, otherwise
 * by plate (then an empty VIN of the vehicle is filled in). {@code lastDownloadAt} is always the newest download
 * among the files of that card or vehicle, so deleting a file recalculates it. Names and card numbers from the
 * files never go to the log.
 */
@Service
public class TachoImportService {

    public static final String MODULE = "tacho";
    public static final int MAX_FILES = 50;
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    static final String STORED_KIND = "TACHO";

    private static final Logger log = LoggerFactory.getLogger(TachoImportService.class);

    private final TachoFileRepository files;
    private final StoredFileRepository storedFiles;
    private final FileStorage storage;
    private final DriverRepository drivers;
    private final DriverCardRepository cards;
    private final VehicleRepository vehicles;
    private final AppUserRepository users;
    private final DriverService driverService;
    private final VehicleService vehicleService;
    private final AuditService audit;
    private final LangService lang;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public TachoImportService(TachoFileRepository files, StoredFileRepository storedFiles, FileStorage storage,
                              DriverRepository drivers, DriverCardRepository cards, VehicleRepository vehicles,
                              AppUserRepository users, DriverService driverService, VehicleService vehicleService,
                              AuditService audit, LangService lang, ObjectMapper json,
                              PlatformTransactionManager transactionManager) {
        this.files = files;
        this.storedFiles = storedFiles;
        this.storage = storage;
        this.drivers = drivers;
        this.cards = cards;
        this.vehicles = vehicles;
        this.users = users;
        this.driverService = driverService;
        this.vehicleService = vehicleService;
        this.audit = audit;
        this.lang = lang;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** One uploaded part: the name the browser sent and the bytes (null when the part is too large). */
    public record Upload(String fileName, long size, byte[] bytes) {
    }

    // --- upload --------------------------------------------------------------------------------------------

    @RequiresPrivilege(Privileges.TACHO_FILE_UPLOAD)
    public List<TachoFileDto.ImportResult> upload(List<Upload> uploads) {
        InputFields.requireFirmaId();
        if (uploads == null || uploads.isEmpty()) {
            throw ApiException.validation(Map.of("files", ErrorCodes.FIELD_REQUIRED));
        }
        if (uploads.size() > MAX_FILES) {
            throw new ApiException(HttpStatus.valueOf(413), ErrorCodes.TACHO_TOO_MANY);
        }
        List<TachoFileDto.ImportResult> results = new ArrayList<>();
        for (Upload upload : uploads) {
            results.add(importOne(upload));
        }
        return results;
    }

    private TachoFileDto.ImportResult importOne(Upload upload) {
        String name = safeName(upload.fileName());
        if (upload.bytes() == null || upload.size() > MAX_FILE_BYTES) {
            return rejected(name, ErrorCodes.TACHO_TOO_LARGE);
        }
        String sha256 = sha256(upload.bytes());
        TachoFileRecord known = tx.execute(status -> files.findBySha256(sha256).orElse(null));
        if (known != null) {
            return duplicate(name, known);
        }
        TachoFile parsed;
        try {
            parsed = TachoParser.parse(upload.bytes());
        } catch (TachoFormatException e) {
            return rejected(name, switch (e.getReason()) {
                case NOT_A_TACHOGRAPH_FILE -> ErrorCodes.TACHO_NOT_TACHOGRAPH;
                case TRUNCATED -> ErrorCodes.TACHO_TRUNCATED;
                case UNSUPPORTED -> ErrorCodes.TACHO_UNSUPPORTED;
                case CORRUPT -> ErrorCodes.TACHO_CORRUPT;
            });
        } catch (RuntimeException e) {
            // A parser bug must not turn into a 500 for the whole upload; the message holds no personal data.
            log.warn("DDD parser failed on an uploaded file: {}", e.toString());
            return rejected(name, ErrorCodes.TACHO_CORRUPT);
        }
        try {
            TachoFileRecord saved = tx.execute(status -> store(name, upload.bytes(), sha256, parsed));
            return new TachoFileDto.ImportResult(name, TachoFileDto.ImportResult.Status.IMPORTED,
                    tx.execute(status -> toDto(saved)), null);
        } catch (DataIntegrityViolationException e) {
            // The same file in two requests at the same time: the unique key (firma_id, sha256) decides.
            TachoFileRecord other = tx.execute(status -> files.findBySha256(sha256).orElse(null));
            if (other == null) {
                throw e;
            }
            return duplicate(name, other);
        }
    }

    private TachoFileRecord store(String name, byte[] bytes, String sha256, TachoFile parsed) {
        Long firmaId = InputFields.requireFirmaId();
        Long userId = CurrentUser.require().id();
        StoredFile stored = storage.store(firmaId, STORED_KIND, name, "application/octet-stream", bytes, sha256, userId);
        TachoFileSummary s = parsed.summary();
        TachoFileRecord f = new TachoFileRecord();
        f.setFirmaId(firmaId);
        f.setKind(TachoFileRecord.Kind.valueOf(parsed.kind().name()));
        f.setGeneration(TachoFileRecord.Generation.valueOf(parsed.generation().name()));
        f.setFileId(stored.getId());
        f.setSha256(sha256);
        f.setCardNumber(s.cardNumber());
        f.setCardIssuingCountry(country(s.cardIssuingCountry()));
        f.setCardValidTo(s.cardExpiry());
        f.setVin(blankToUpper(s.vin()));
        f.setPlate(plate(s.plate()));
        f.setPlateCountry(country(s.plateCountry()));
        f.setPeriodFrom(s.periodFrom());
        f.setPeriodTo(s.periodTo());
        f.setDownloadedAt(s.downloadedAt());
        f.setWarningsCount(parsed.warnings().size());
        f.setWarnings(parsed.warnings().isEmpty() ? null : json.writeValueAsString(parsed.warnings()));
        f.setUploadedBy(userId);
        f.setUploadedAt(stored.getUploadedAt());
        f = files.saveAndFlush(f);
        if (f.getKind() == TachoFileRecord.Kind.DRIVER_CARD) {
            matchCard(f);
        } else {
            matchVehicle(f);
        }
        audit.record(fileEvent("file_imported", "Imported", f, name));
        return f;
    }

    // --- matching ------------------------------------------------------------------------------------------

    private void matchCard(TachoFileRecord f) {
        String number = f.getCardNumber();
        if (number == null || number.isBlank()) {
            return;
        }
        DriverCard card = cards.findByCardNumber(number).orElse(null);
        if (card != null) {
            assignToDriver(f, card.getDriverId(), TachoFileRecord.Match.CARD_NUMBER);
            return;
        }
        if (number.length() < 14) {
            return;
        }
        List<DriverCard> sameDriver = cards.findByCardNumberStartingWith(number.substring(0, 14));
        if (sameDriver.size() != 1) {
            return;
        }
        card = sameDriver.getFirst();
        // A renewed or replaced card: take it over unless the file shows an older card than the one on record.
        boolean newer = card.getValidTo() == null || (f.getCardValidTo() != null
                && f.getCardValidTo().isAfter(card.getValidTo()));
        if (newer) {
            String previous = card.getCardNumber();
            card.setCardNumber(number);
            if (f.getCardIssuingCountry() != null) {
                card.setIssuingCountry(f.getCardIssuingCountry());
            }
            card.setValidTo(f.getCardValidTo());
            card.setValidFrom(null);
            card.setGeneration(cardGeneration(f.getGeneration()));
            card.setLastDownloadAt(null);
            cards.save(card);
            audit.record(AuditEvent.of(MODULE, "card_replaced", "Driver card replaced from a tachograph file")
                    .entity("driver", card.getDriverId())
                    .detail("previousCard", previous)
                    .detail("card", number));
        }
        assignToDriver(f, card.getDriverId(), TachoFileRecord.Match.DRIVER_IDENTIFICATION);
    }

    private void matchVehicle(TachoFileRecord f) {
        Vehicle vehicle = null;
        TachoFileRecord.Match match = TachoFileRecord.Match.NONE;
        if (f.getVin() != null) {
            vehicle = vehicles.findByVinAndDeletedFalse(f.getVin()).stream()
                    .filter(v -> !v.getKind().isTrailer()).findFirst().orElse(null);
            match = TachoFileRecord.Match.VIN;
        }
        if (vehicle == null && f.getPlate() != null) {
            String key = VehicleService.plateKey(f.getPlate());
            vehicle = vehicles.findByDeletedFalseOrderByPlateAsc().stream()
                    .filter(v -> !v.getKind().isTrailer() && VehicleService.plateKey(v.getPlate()).equals(key))
                    .findFirst().orElse(null);
            match = TachoFileRecord.Match.PLATE;
        }
        if (vehicle == null) {
            return;
        }
        assignToVehicle(f, vehicle, match);
    }

    private void assignToDriver(TachoFileRecord f, Long driverId, TachoFileRecord.Match match) {
        Long previous = f.getDriverId();
        f.setDriverId(driverId);
        f.setVehicleId(null);
        f.setMatch(match);
        files.saveAndFlush(f);
        refreshDriver(driverId);
        if (previous != null && !previous.equals(driverId)) {
            refreshDriver(previous);
        }
    }

    private void assignToVehicle(TachoFileRecord f, Vehicle vehicle, TachoFileRecord.Match match) {
        Long previous = f.getVehicleId();
        f.setVehicleId(vehicle.getId());
        f.setDriverId(null);
        f.setMatch(match);
        files.saveAndFlush(f);
        boolean changed = false;
        if (vehicle.getVin() == null && f.getVin() != null) {
            vehicle.setVin(f.getVin());
            changed = true;
        }
        if (vehicle.getTachoGen() == Vehicle.TachoGen.NONE) {
            vehicle.setTachoGen(f.getGeneration() == TachoFileRecord.Generation.GEN1
                    ? Vehicle.TachoGen.GEN1 : Vehicle.TachoGen.GEN2);
            changed = true;
        }
        if (changed) {
            vehicles.save(vehicle);
        }
        refreshVehicle(vehicle.getId());
        if (previous != null && !previous.equals(vehicle.getId())) {
            refreshVehicle(previous);
        }
    }

    /** driver_card.last_download_at = newest download among the driver's files of that card. */
    private void refreshDriver(Long driverId) {
        cards.findByDriverIdOrderByIdDesc(driverId).stream().findFirst().ifPresent(card -> {
            Instant last = files.lastDownloadOfCard(driverId, card.getCardNumber(), TachoFileRecord.Kind.DRIVER_CARD);
            if (!Objects.equals(last, card.getLastDownloadAt())) {
                card.setLastDownloadAt(last);
                cards.save(card);
            }
        });
    }

    /** vehicle.last_vu_download_at = newest download among the vehicle's VU files. */
    private void refreshVehicle(Long vehicleId) {
        vehicles.findById(vehicleId).ifPresent(vehicle -> {
            Instant last = files.lastDownloadOfVehicle(vehicleId, TachoFileRecord.Kind.VEHICLE_UNIT);
            if (!Objects.equals(last, vehicle.getLastVuDownloadAt())) {
                vehicle.setLastVuDownloadAt(last);
                vehicles.save(vehicle);
            }
        });
    }

    // --- journal -------------------------------------------------------------------------------------------

    @RequiresPrivilege({Privileges.TACHO_HOS_VIEW, Privileges.TACHO_FILE_UPLOAD})
    @Transactional(readOnly = true)
    public TachoFileDto.Page list(String kind, Long driverId, Long vehicleId, boolean unassigned, LocalDate from,
                                  LocalDate to, int page, int size) {
        InputFields.requireFirmaId();
        Map<String, String> errors = new LinkedHashMap<>();
        TachoFileRecord.Kind k = InputFields.parseEnum(TachoFileRecord.Kind.class, "kind", kind, false, errors);
        if (page < 0) {
            errors.put("page", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (size < 1 || size > 200) {
            errors.put("size", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        org.springframework.data.domain.Page<TachoFileRecord> result =
                files.search(k, driverId, vehicleId, unassigned, from, to, PageRequest.of(page, size));
        return new TachoFileDto.Page(toDtos(result.getContent()), result.getTotalElements());
    }

    @RequiresPrivilege({Privileges.TACHO_HOS_VIEW, Privileges.TACHO_FILE_UPLOAD})
    @Transactional(readOnly = true)
    public TachoFileDto.Detail detail(Long id) {
        TachoFileRecord f = load(id);
        StoredFile stored = storedFiles.findById(f.getFileId()).orElseThrow();
        TachoFile parsed = TachoParser.parse(storage.read(stored));
        TachoFileDto.Counts counts;
        List<TachoFileDto.VuDriver> vuDrivers = List.of();
        if (parsed instanceof DriverCardFile card) {
            counts = new TachoFileDto.Counts(card.activityDays().size(), card.vehiclesUsed().size(),
                    card.places().size(), card.events().size(), card.faults().size(), 0, 0);
        } else {
            VuFile vu = (VuFile) parsed;
            List<VuActivityDay> days = vu.activityDays();
            counts = new TachoFileDto.Counts(days.size(), 0,
                    days.stream().mapToInt(d -> d.places().size()).sum(),
                    vu.eventsAndFaults() == null ? 0 : vu.eventsAndFaults().events().size(),
                    vu.eventsAndFaults() == null ? 0 : vu.eventsAndFaults().faults().size(),
                    days.stream().mapToInt(d -> d.cardInsertions().size()).sum(),
                    vu.technicalData() == null ? 0 : vu.technicalData().calibrations().size());
            vuDrivers = vuDrivers(days);
        }
        Map<String, Object> suggestion = f.getMatch() == TachoFileRecord.Match.NONE ? suggestion(f, parsed) : null;
        return new TachoFileDto.Detail(toDto(f), parsed.warnings(), counts, suggestion, vuDrivers);
    }

    /** The original bytes and name, for the download button. */
    public record Content(String originalName, byte[] bytes) {
    }

    @RequiresPrivilege(Privileges.TACHO_FILE_UPLOAD)
    @Transactional(readOnly = true)
    public Content content(Long id) {
        TachoFileRecord f = load(id);
        StoredFile stored = storedFiles.findById(f.getFileId()).orElseThrow();
        return new Content(stored.getOriginalName(), storage.read(stored));
    }

    // --- manual assignment and creation --------------------------------------------------------------------

    @RequiresPrivilege(Privileges.TACHO_FILE_UPLOAD)
    @Transactional
    public TachoFileDto assign(Long id, TachoFileDto.AssignmentInput input) {
        TachoFileRecord f = load(id);
        Long firmaId = f.getFirmaId();
        if (f.getKind() == TachoFileRecord.Kind.DRIVER_CARD) {
            Long driverId = input == null ? null : input.driverId();
            if (driverId == null) {
                throw ApiException.validation(Map.of("driverId", ErrorCodes.FIELD_REQUIRED));
            }
            Driver driver = drivers.findByIdAndDeletedFalse(driverId).filter(d -> firmaId.equals(d.getFirmaId()))
                    .orElseThrow(() -> ApiException.validation(Map.of("driverId", ErrorCodes.FIELD_INVALID_VALUE)));
            ensureCard(driver, f);
            assignToDriver(f, driver.getId(), TachoFileRecord.Match.MANUAL);
        } else {
            Long vehicleId = input == null ? null : input.vehicleId();
            if (vehicleId == null) {
                throw ApiException.validation(Map.of("vehicleId", ErrorCodes.FIELD_REQUIRED));
            }
            Vehicle vehicle = vehicles.findByIdAndDeletedFalse(vehicleId)
                    .filter(v -> firmaId.equals(v.getFirmaId()) && !v.getKind().isTrailer())
                    .orElseThrow(() -> ApiException.validation(Map.of("vehicleId", ErrorCodes.FIELD_INVALID_VALUE)));
            assignToVehicle(f, vehicle, TachoFileRecord.Match.MANUAL);
        }
        audit.record(fileEvent("file_assigned", "Assigned", f, originalName(f)));
        return toDto(f);
    }

    /** A driver without a card gets the card of the file; another driver's card number is a 422. */
    private void ensureCard(Driver driver, TachoFileRecord f) {
        if (f.getCardNumber() == null || !cards.findByDriverIdOrderByIdDesc(driver.getId()).isEmpty()) {
            return;
        }
        if (cards.findByCardNumber(f.getCardNumber()).isPresent()) {
            throw ApiException.validation(Map.of("driverId", ErrorCodes.DRIVER_CARD_TAKEN));
        }
        DriverCard card = new DriverCard();
        card.setFirmaId(driver.getFirmaId());
        card.setDriverId(driver.getId());
        card.setCardNumber(f.getCardNumber());
        card.setIssuingCountry(f.getCardIssuingCountry() == null ? "XX" : f.getCardIssuingCountry());
        card.setValidTo(f.getCardValidTo());
        card.setGeneration(cardGeneration(f.getGeneration()));
        cards.save(card);
    }

    /** Creates the driver described by an unassigned driver card file and assigns the file. */
    @RequiresPrivilege(value = {Privileges.TACHO_FILE_UPLOAD, Privileges.DRIVER_MANAGE}, all = true)
    @Transactional
    public TachoFileDto.CreatedDriver createDriver(Long id) {
        TachoFileRecord f = load(id);
        if (f.getKind() != TachoFileRecord.Kind.DRIVER_CARD) {
            throw ApiException.badRequest(ErrorCodes.BAD_REQUEST);
        }
        if (f.getDriverId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        DriverCardFile card = (DriverCardFile) TachoParser.parse(storage.read(storedFiles.findById(f.getFileId())
                .orElseThrow()));
        DriverCardHolder holder = card.holder();
        String lastName = holder == null ? null : titleCase(holder.surname());
        String firstName = holder == null ? null : titleCase(holder.firstNames());
        DriverDto created = driverService.create(new DriverInput(
                firstName == null ? "?" : firstName, lastName == null ? f.getCardNumber() : lastName,
                holder == null ? null : holder.birthDate(), null, null, f.getCardIssuingCountry(), null, null, null,
                null, f.getCardNumber(), f.getCardIssuingCountry() == null ? "XX" : f.getCardIssuingCountry(),
                f.getCardValidTo()));
        cards.findByDriverIdOrderByIdDesc(created.id()).stream().findFirst().ifPresent(c -> {
            c.setGeneration(cardGeneration(f.getGeneration()));
            cards.save(c);
        });
        assignToDriver(f, created.id(), TachoFileRecord.Match.MANUAL);
        audit.record(fileEvent("file_assigned", "Created a driver from", f, originalName(f)));
        return new TachoFileDto.CreatedDriver(driverService.get(created.id()), toDto(f));
    }

    /** Creates the vehicle (kind TRUCK) described by an unassigned vehicle unit file and assigns the file. */
    @RequiresPrivilege(value = {Privileges.TACHO_FILE_UPLOAD, Privileges.FLEET_VEHICLE_MANAGE}, all = true)
    @Transactional
    public TachoFileDto.CreatedVehicle createVehicle(Long id) {
        TachoFileRecord f = load(id);
        if (f.getKind() != TachoFileRecord.Kind.VEHICLE_UNIT) {
            throw ApiException.badRequest(ErrorCodes.BAD_REQUEST);
        }
        if (f.getVehicleId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        VehicleDto created = vehicleService.create(new VehicleInput(Vehicle.Kind.TRUCK.name(), f.getPlate(),
                f.getVin(), null, null, null, null, null, null, null, null, vehicleGeneration(f).name(), null,
                Vehicle.Status.ACTIVE.name(), null, null));
        Vehicle vehicle = vehicles.findById(created.id()).orElseThrow();
        assignToVehicle(f, vehicle, TachoFileRecord.Match.MANUAL);
        audit.record(fileEvent("file_assigned", "Created a vehicle from", f, originalName(f)));
        return new TachoFileDto.CreatedVehicle(vehicleService.get(created.id()), toDto(f));
    }

    // --- delete --------------------------------------------------------------------------------------------

    /** Deletes the file and everything derived from it; lastDownloadAt is recalculated from the other files. */
    @RequiresPrivilege(Privileges.TACHO_FILE_UPLOAD)
    @Transactional
    public void delete(Long id) {
        TachoFileRecord f = load(id);
        String name = originalName(f);
        Long driverId = f.getDriverId();
        Long vehicleId = f.getVehicleId();
        StoredFile stored = storedFiles.findById(f.getFileId()).orElse(null);
        files.delete(f);
        files.flush();
        if (stored != null) {
            storage.delete(stored);
        }
        if (driverId != null) {
            refreshDriver(driverId);
        }
        if (vehicleId != null) {
            refreshVehicle(vehicleId);
        }
        audit.record(fileEvent("file_deleted", "Deleted", f, name));
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private TachoFileRecord load(Long id) {
        Long firmaId = InputFields.requireFirmaId();
        return files.findById(id).filter(f -> firmaId.equals(f.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private TachoFileDto toDto(TachoFileRecord f) {
        return toDtos(List.of(f)).getFirst();
    }

    private List<TachoFileDto> toDtos(List<TachoFileRecord> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, StoredFile> stored = byId(storedFiles.findAllById(ids(list, TachoFileRecord::getFileId)),
                StoredFile::getId);
        Map<Long, Driver> driverOf = byId(drivers.findAllById(ids(list, TachoFileRecord::getDriverId)), Driver::getId);
        Map<Long, Vehicle> vehicleOf = byId(vehicles.findAllById(ids(list, TachoFileRecord::getVehicleId)),
                Vehicle::getId);
        Map<Long, AppUser> userOf = byId(users.findAllById(ids(list, TachoFileRecord::getUploadedBy)), AppUser::getId);
        return list.stream().map(f -> TachoFileDto.of(f, stored.get(f.getFileId()),
                f.getDriverId() == null ? null : driverOf.get(f.getDriverId()),
                f.getVehicleId() == null ? null : vehicleOf.get(f.getVehicleId()),
                f.getUploadedBy() == null ? null : userOf.get(f.getUploadedBy()))).toList();
    }

    private static List<Long> ids(List<TachoFileRecord> list, Function<TachoFileRecord, Long> id) {
        return list.stream().map(id).filter(Objects::nonNull).distinct().toList();
    }

    private static <T> Map<Long, T> byId(List<T> items, Function<T, Long> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }

    private String originalName(TachoFileRecord f) {
        return storedFiles.findById(f.getFileId()).map(StoredFile::getOriginalName).orElse("");
    }

    /** The data for "create from file" in the screen; only for files without a driver or vehicle. */
    private static Map<String, Object> suggestion(TachoFileRecord f, TachoFile parsed) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (parsed instanceof DriverCardFile card) {
            DriverCardHolder holder = card.holder();
            s.put("firstName", holder == null ? null : titleCase(holder.firstNames()));
            s.put("lastName", holder == null ? null : titleCase(holder.surname()));
            s.put("birthDate", holder == null ? null : holder.birthDate());
            s.put("cardNumber", f.getCardNumber());
            s.put("cardIssuingCountry", f.getCardIssuingCountry());
            s.put("cardValidTo", f.getCardValidTo());
        } else {
            s.put("plate", f.getPlate());
            s.put("plateCountry", f.getPlateCountry());
            s.put("vin", f.getVin());
            s.put("tachoGen", vehicleGeneration(f).name());
        }
        return s;
    }

    /** The driver cards inserted into the vehicle unit, with the first insertion and the last withdrawal. */
    private List<TachoFileDto.VuDriver> vuDrivers(List<VuActivityDay> days) {
        Map<String, List<VuCardInsertion>> byCard = new LinkedHashMap<>();
        for (VuActivityDay day : days) {
            for (VuCardInsertion insertion : day.cardInsertions()) {
                if (insertion.card() != null && insertion.card().isDriverCard() && insertion.card().number() != null) {
                    byCard.computeIfAbsent(insertion.card().number(), k -> new ArrayList<>()).add(insertion);
                }
            }
        }
        List<TachoFileDto.VuDriver> result = new ArrayList<>();
        for (Map.Entry<String, List<VuCardInsertion>> entry : byCard.entrySet()) {
            List<VuCardInsertion> list = entry.getValue();
            VuCardInsertion any = list.getFirst();
            String name = (Objects.toString(titleCase(any.holderFirstNames()), "") + " "
                    + Objects.toString(titleCase(any.holderSurname()), "")).trim();
            Instant first = list.stream().map(VuCardInsertion::insertionTime).filter(Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
            Instant last = list.stream().map(VuCardInsertion::withdrawalTime).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            Long driverId = cards.findByCardNumber(entry.getKey()).map(DriverCard::getDriverId).orElse(null);
            result.add(new TachoFileDto.VuDriver(entry.getKey(), name, driverId, first, last));
        }
        result.sort(Comparator.comparing(TachoFileDto.VuDriver::firstInsertion,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    private TachoFileDto.ImportResult duplicate(String name, TachoFileRecord known) {
        return new TachoFileDto.ImportResult(name, TachoFileDto.ImportResult.Status.DUPLICATE,
                tx.execute(status -> toDto(known)), null);
    }

    private TachoFileDto.ImportResult rejected(String name, String code) {
        return new TachoFileDto.ImportResult(name, TachoFileDto.ImportResult.Status.REJECTED, null,
                new TachoFileDto.ImportResult.Error(code, lang.get(code)));
    }

    private static AuditEvent fileEvent(String action, String verb, TachoFileRecord f, String name) {
        AuditEvent event = AuditEvent.of(MODULE, action, verb + " tachograph file " + name)
                .entity("tacho_file", f.getId())
                .detail("kind", f.getKind().name())
                .detail("fileName", name)
                .detail("sha256", f.getSha256())
                .detail("periodFrom", f.getPeriodFrom() == null ? "" : f.getPeriodFrom().toString())
                .detail("periodTo", f.getPeriodTo() == null ? "" : f.getPeriodTo().toString());
        if (f.getDriverId() != null) {
            event = event.detail("driverId", f.getDriverId());
        }
        if (f.getVehicleId() != null) {
            event = event.detail("vehicleId", f.getVehicleId());
        }
        return event;
    }

    private static Vehicle.TachoGen vehicleGeneration(TachoFileRecord f) {
        return f.getGeneration() == TachoFileRecord.Generation.GEN1 ? Vehicle.TachoGen.GEN1 : Vehicle.TachoGen.GEN2;
    }

    private static DriverCard.Generation cardGeneration(TachoFileRecord.Generation generation) {
        return generation == TachoFileRecord.Generation.GEN1 ? DriverCard.Generation.GEN1 : DriverCard.Generation.GEN2;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The browser's file name without a path, at most 255 characters. */
    static String safeName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty()) {
            name = "upload.ddd";
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    /** ISO alpha-2 or null (the parser falls back to the tachograph code when a state has no ISO code). */
    private static String country(String code) {
        return code != null && code.length() == 2 ? code.toUpperCase(Locale.ROOT) : null;
    }

    private static String blankToUpper(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    /** The plate as the fleet stores it (upper-case, single spaces), at most 20 characters. */
    private static String plate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String plate = raw.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return plate.length() > 20 ? plate.substring(0, 20) : plate;
    }

    /** "KOWALSKI-NOWAK" -> "Kowalski-Nowak": names on cards are upper-case. */
    static String titleCase(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String lower = raw.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean start = true;
        for (char c : lower.toCharArray()) {
            out.append(start ? Character.toTitleCase(c) : c);
            start = c == ' ' || c == '-' || c == '\'';
        }
        return out.toString();
    }
}

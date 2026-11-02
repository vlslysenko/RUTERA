package com.rutera.core.trip;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.StoredFile;
import com.rutera.common.domain.Trip;
import com.rutera.common.domain.TripFile;
import com.rutera.common.domain.TripStop;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.StoredFileRepository;
import com.rutera.common.repository.TripFileRepository;
import com.rutera.common.repository.TripRepository;
import com.rutera.common.repository.TripStopRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.driver.DriverSelf;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.storage.FileStorage;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Photos and documents of a trip ({@code /app/trips/{id}/files}): CMR photos, delivery notes and receipts from the
 * driver's phone or the office. JPEG, PNG, WebP, HEIC and PDF only (by content), up to 15 MB. The office (trip view,
 * trip manage or CMR upload) sees and manages all of them; a driver only on its own trips, uploads while the trip
 * is open and deletes only its own uploads then.
 */
@Service
public class TripFileService {

    public static final String MODULE = "trip";
    static final String STORED_KIND = "TRIP";
    static final int MAX_BYTES = 15 * 1024 * 1024;

    private final TripRepository trips;
    private final TripStopRepository stops;
    private final TripFileRepository files;
    private final StoredFileRepository storedFiles;
    private final AppUserRepository users;
    private final FileStorage storage;
    private final DriverSelf self;
    private final AuditService audit;

    public TripFileService(TripRepository trips, TripStopRepository stops, TripFileRepository files,
                           StoredFileRepository storedFiles, AppUserRepository users, FileStorage storage,
                           DriverSelf self, AuditService audit) {
        this.trips = trips;
        this.stops = stops;
        this.files = files;
        this.storedFiles = storedFiles;
        this.users = users;
        this.storage = storage;
        this.self = self;
        this.audit = audit;
    }

    public record Ref(Long id, String name) {
    }

    public record StopRef(Long id, int seq) {
    }

    public record FileDto(Long id, TripFile.Kind kind, String note, StopRef stop, String fileName, String contentType,
                          long size, Ref uploadedBy, Instant createdAt) {
    }

    public record Content(String fileName, String contentType, byte[] bytes) {
    }

    @RequiresPrivilege({Privileges.TRIP_TRIP_VIEW, Privileges.DRIVER_APP_SELF_USE})
    @Transactional(readOnly = true)
    public List<FileDto> list(Long tripId) {
        Trip trip = viewable(tripId);
        return toDtos(files.findByTripIdOrderByIdDesc(trip.getId()));
    }

    @RequiresPrivilege({Privileges.TRIP_TRIP_VIEW, Privileges.DRIVER_APP_SELF_USE})
    @Transactional(readOnly = true)
    public Content content(Long tripId, Long fileId) {
        TripFile f = load(viewable(tripId), fileId);
        StoredFile stored = storedFiles.findById(f.getFileId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
        return new Content(stored.getOriginalName(), stored.getMime(), storage.read(stored));
    }

    @RequiresPrivilege({Privileges.TRIP_TRIP_MANAGE, Privileges.TRIP_CMR_UPLOAD, Privileges.DRIVER_APP_SELF_USE})
    @Transactional
    public FileDto upload(Long tripId, String fileName, byte[] bytes, String kind, String note, Long stopId) {
        Trip trip = load(tripId);
        if (!office()) {
            self.requireCrew(trip.getId());
            open(trip);
        }
        Map<String, String> errors = new LinkedHashMap<>();
        TripFile.Kind k = InputFields.parseEnum(TripFile.Kind.class, "kind", kind, false, errors);
        String type = null;
        if (bytes == null || bytes.length == 0) {
            errors.put("file", ErrorCodes.FIELD_REQUIRED);
        } else if (bytes.length > MAX_BYTES) {
            errors.put("file", ErrorCodes.TRIP_FILE_TOO_LARGE);
        } else {
            type = contentType(bytes);
            if (type == null) {
                errors.put("file", ErrorCodes.TRIP_FILE_TYPE);
            }
        }
        if (note != null && note.length() > 500) {
            errors.put("note", ErrorCodes.FIELD_TOO_LONG);
        }
        TripStop stop = null;
        if (stopId != null) {
            stop = stops.findById(stopId).filter(s -> s.getTripId().equals(trip.getId())).orElse(null);
            if (stop == null) {
                errors.put("stopId", ErrorCodes.FIELD_INVALID_VALUE);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        String name = fileName == null || fileName.isBlank() ? "file" : fileName.strip();
        name = name.length() > 200 ? name.substring(name.length() - 200) : name;
        Long userId = CurrentUser.require().id();
        StoredFile stored = storage.store(trip.getFirmaId(), STORED_KIND, name, type, bytes, sha256(bytes), userId);
        TripFile f = new TripFile();
        f.setFirmaId(trip.getFirmaId());
        f.setTripId(trip.getId());
        f.setStopId(stop == null ? null : stop.getId());
        f.setKind(k == null ? TripFile.Kind.OTHER : k);
        f.setNote(InputFields.blankToNull(note));
        f.setFileId(stored.getId());
        f.setUploadedBy(userId);
        f = files.save(f);
        audit.record(AuditEvent.of(MODULE, "trip_file_added", "Added " + f.getKind().name() + " to trip "
                + trip.getNumber()).entity("trip_file", f.getId()).detail("tripId", trip.getId()));
        return toDtos(List.of(f)).getFirst();
    }

    @RequiresPrivilege({Privileges.TRIP_TRIP_MANAGE, Privileges.DRIVER_APP_SELF_USE})
    @Transactional
    public void delete(Long tripId, Long fileId) {
        Trip trip = load(tripId);
        TripFile f = load(trip, fileId);
        if (!self.full(Privileges.TRIP_TRIP_MANAGE)) {
            self.requireCrew(trip.getId());
            if (!Objects.equals(f.getUploadedBy(), CurrentUser.require().id())) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED);
            }
            open(trip);
        }
        Long storedId = f.getFileId();
        files.delete(f);
        files.flush();
        storedFiles.findById(storedId).ifPresent(storage::delete);
        audit.record(AuditEvent.of(MODULE, "trip_file_deleted", "Deleted a file of trip " + trip.getNumber())
                .entity("trip_file", f.getId()).detail("tripId", trip.getId()));
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private boolean office() {
        return self.full(Privileges.TRIP_TRIP_MANAGE) || self.full(Privileges.TRIP_CMR_UPLOAD);
    }

    /** The trip for reading: the office with trip view, a driver only when on the crew. */
    private Trip viewable(Long tripId) {
        Trip trip = load(tripId);
        if (!self.full(Privileges.TRIP_TRIP_VIEW)) {
            self.requireCrew(trip.getId());
        }
        return trip;
    }

    /** A driver adds and removes files only while the trip is running or planned. */
    private static void open(Trip trip) {
        if (trip.getStatus() == Trip.Status.DONE || trip.getStatus() == Trip.Status.CANCELLED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.TRIP_FILE_TRIP_CLOSED);
        }
    }

    private Trip load(Long id) {
        Long firmaId = InputFields.requireFirmaId();
        return trips.findById(id).filter(t -> firmaId.equals(t.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private TripFile load(Trip trip, Long fileId) {
        return files.findById(fileId).filter(f -> f.getTripId().equals(trip.getId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    /** JPEG, PNG, WebP, HEIC or PDF by the first bytes; null for anything else. */
    static String contentType(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
            return "application/pdf";
        }
        String head = new String(b, 0, Math.min(b.length, 12), StandardCharsets.ISO_8859_1);
        if (head.length() == 12 && head.startsWith("RIFF") && head.endsWith("WEBP")) {
            return "image/webp";
        }
        if (head.length() == 12 && head.startsWith("ftyp", 4)
                && List.of("heic", "heix", "hevc", "mif1", "msf1", "heim", "heis").contains(head.substring(8))) {
            return "image/heic";
        }
        return null;
    }

    private List<FileDto> toDtos(List<TripFile> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, StoredFile> storedOf = byId(storedFiles.findAllById(list.stream().map(TripFile::getFileId)
                .toList()), StoredFile::getId);
        Map<Long, TripStop> stopOf = byId(stops.findAllById(list.stream().map(TripFile::getStopId)
                .filter(Objects::nonNull).toList()), TripStop::getId);
        Map<Long, AppUser> userOf = byId(users.findAllById(list.stream().map(TripFile::getUploadedBy)
                .filter(Objects::nonNull).toList()), AppUser::getId);
        return list.stream().map(f -> {
            StoredFile s = storedOf.get(f.getFileId());
            TripStop stop = f.getStopId() == null ? null : stopOf.get(f.getStopId());
            AppUser u = f.getUploadedBy() == null ? null : userOf.get(f.getUploadedBy());
            return new FileDto(f.getId(), f.getKind(), f.getNote(), stop == null ? null
                    : new StopRef(stop.getId(), stop.getSeq()), s == null ? null : s.getOriginalName(),
                    s == null ? null : s.getMime(), s == null ? 0 : s.getSizeBytes(),
                    u == null ? null : new Ref(u.getId(), u.getFullName().strip()), f.getCreatedAt());
        }).toList();
    }

    private static <T> Map<Long, T> byId(List<T> items, Function<T, Long> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

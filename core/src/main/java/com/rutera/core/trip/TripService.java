package com.rutera.core.trip;

import com.rutera.common.domain.Contractor;
import com.rutera.common.domain.Driver;
import com.rutera.common.domain.TransportOrder;
import com.rutera.common.domain.Trip;
import com.rutera.common.domain.TripDriver;
import com.rutera.common.domain.TripStop;
import com.rutera.common.domain.Vehicle;
import com.rutera.common.repository.CmrDocumentRepository;
import com.rutera.common.repository.ContractorRepository;
import com.rutera.common.repository.DriverRepository;
import com.rutera.common.repository.FuelTransactionRepository;
import com.rutera.common.repository.InvoiceRepository;
import com.rutera.common.repository.TransportOrderRepository;
import com.rutera.common.repository.TripDriverRepository;
import com.rutera.common.repository.TripRepository;
import com.rutera.common.repository.TripStopRepository;
import com.rutera.common.repository.VehicleRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.driver.DriverSelf;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Trips of the firma ({@code /app/trips}) with crew, orders and stops. A PUT replaces everything: the crew, the
 * orders on the trip (an order taken off goes back to NEW) and the stops (stops with an id are updated, the others
 * created, missing ones deleted). The orders follow the trip's status: PLANNED -> PLANNED, RUNNING -> IN_PROGRESS,
 * DONE -> DONE; a cancelled trip lets its orders go. CANCELLED and INVOICED orders keep their status.
 */
@Service
public class TripService {

    public static final String MODULE = "trip";

    private final TripRepository trips;
    private final TripDriverRepository crew;
    private final TripStopRepository stops;
    private final TransportOrderRepository orders;
    private final ContractorRepository contractors;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final CmrDocumentRepository cmrs;
    private final FuelTransactionRepository fuel;
    private final InvoiceRepository invoices;
    private final DriverSelf self;
    private final AuditService audit;

    public TripService(TripRepository trips, TripDriverRepository crew, TripStopRepository stops,
                       TransportOrderRepository orders, ContractorRepository contractors, VehicleRepository vehicles,
                       DriverRepository drivers, CmrDocumentRepository cmrs, FuelTransactionRepository fuel,
                       InvoiceRepository invoices, DriverSelf self, AuditService audit) {
        this.self = self;
        this.cmrs = cmrs;
        this.fuel = fuel;
        this.invoices = invoices;
        this.trips = trips;
        this.crew = crew;
        this.stops = stops;
        this.orders = orders;
        this.contractors = contractors;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.audit = audit;
    }

    // --- read ----------------------------------------------------------------------------------------------

    @RequiresPrivilege({Privileges.TRIP_TRIP_VIEW, Privileges.DRIVER_APP_SELF_USE})
    @Transactional(readOnly = true)
    public TripDto.Page list(String status, Long vehicleId, Long driverId, LocalDate from, LocalDate to, String q,
                             int page, int size) {
        InputFields.requireFirmaId();
        self.check(Privileges.TRIP_TRIP_VIEW, driverId);
        Map<String, String> errors = new LinkedHashMap<>();
        Trip.Status s = InputFields.parseEnum(Trip.Status.class, "status", status, false, errors);
        pageChecks(page, size, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        Instant fromAt = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toAt = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1000);
        org.springframework.data.domain.Page<Trip> result =
                trips.search(s, vehicleId, driverId, fromAt, toAt, like(q), PageRequest.of(page, size));
        return new TripDto.Page(toDtos(result.getContent()), result.getTotalElements());
    }

    @RequiresPrivilege({Privileges.TRIP_TRIP_VIEW, Privileges.DRIVER_APP_SELF_USE})
    @Transactional(readOnly = true)
    public TripDto get(Long id) {
        Trip trip = load(id);
        if (!self.full(Privileges.TRIP_TRIP_VIEW)) {
            requireCrew(trip);
        }
        return toDto(trip);
    }

    /** 403 unless the caller's own driver is MAIN or SECOND on the trip (driver cabinet). */
    private void requireCrew(Trip trip) {
        self.requireCrew(trip.getId());
    }

    // --- write ---------------------------------------------------------------------------------------------

    @RequiresPrivilege(Privileges.TRIP_TRIP_MANAGE)
    @Transactional
    public TripDto create(TripDto.Input input) {
        Trip trip = new Trip();
        trip.setFirmaId(InputFields.requireFirmaId());
        save(trip, input);
        audit.record(AuditEvent.of(MODULE, "trip_created", "Added trip " + trip.getNumber()).entity("trip", trip.getId()));
        return toDto(trip);
    }

    @RequiresPrivilege(Privileges.TRIP_TRIP_MANAGE)
    @Transactional
    public TripDto update(Long id, TripDto.Input input) {
        Trip trip = load(id);
        save(trip, input);
        audit.record(AuditEvent.of(MODULE, "trip_updated", "Updated trip " + trip.getNumber())
                .entity("trip", trip.getId())
                .detail("status", trip.getStatus().name()));
        return toDto(trip);
    }

    /** Only a planned or cancelled trip can be deleted; its orders go back to NEW. */
    @RequiresPrivilege(Privileges.TRIP_TRIP_MANAGE)
    @Transactional
    public void delete(Long id) {
        Trip trip = load(id);
        if (trip.getStatus() != Trip.Status.PLANNED && trip.getStatus() != Trip.Status.CANCELLED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        for (TransportOrder o : orders.findByTripIdOrderByNumberAsc(trip.getId())) {
            release(o);
        }
        stops.deleteAll(stops.findByTripIdOrderBySeqAsc(trip.getId()));
        crew.deleteAll(crew.findByTripId(trip.getId()));
        // CMR notes, fuel transactions and invoices keep their data, only the link to the trip goes
        cmrs.detachTrip(trip.getId());
        fuel.detachTrip(trip.getId());
        invoices.detachTrip(trip.getId());
        stops.flush();
        crew.flush();
        orders.flush();
        trips.delete(trip);
        audit.record(AuditEvent.of(MODULE, "trip_deleted", "Deleted trip " + trip.getNumber()).entity("trip", trip.getId()));
    }

    /**
     * Marks one stop without a full PUT: DONE sets actualAt (now when not given), PLANNED clears it. A driver
     * (driver_app.self/use) marks stops of their own trips as DONE or SKIPPED.
     */
    @RequiresPrivilege({Privileges.TRIP_TRIP_MANAGE, Privileges.DRIVER_APP_SELF_USE})
    @Transactional
    public TripDto.Stop patchStop(Long tripId, Long stopId, TripDto.StopStatusInput input) {
        Trip trip = load(tripId);
        boolean driver = !self.full(Privileges.TRIP_TRIP_MANAGE);
        if (driver) {
            requireCrew(trip);
        }
        TripStop stop = stops.findById(stopId)
                .filter(s -> s.getTripId().equals(trip.getId()) && trip.getFirmaId().equals(s.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
        Map<String, String> errors = new LinkedHashMap<>();
        TripStop.Status status = InputFields.parseEnum(TripStop.Status.class, "status",
                input == null ? null : input.status(), true, errors);
        if (driver && status == TripStop.Status.PLANNED) {
            errors.put("status", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        stop.setStatus(status);
        Instant actualAt = input.actualAt();
        switch (status) {
            case DONE -> stop.setActualAt(actualAt != null ? actualAt
                    : stop.getActualAt() != null ? stop.getActualAt() : Instant.now());
            case PLANNED -> stop.setActualAt(null);
            case SKIPPED -> stop.setActualAt(actualAt);
        }
        stop = stops.save(stop);
        audit.record(AuditEvent.of(MODULE, "stop_marked", "Marked a stop of trip " + trip.getNumber())
                .entity("trip_stop", stop.getId())
                .detail("status", status.name()));
        return TripDto.Stop.of(stop);
    }

    /** A trip for one order in one step: the order's loading and unloading become the stops. */
    @RequiresPrivilege(value = {Privileges.TRIP_ORDER_MANAGE, Privileges.TRIP_TRIP_MANAGE}, all = true)
    @Transactional
    public TripDto createForOrder(Long orderId, OrderDto.TripRequest request) {
        Long firmaId = InputFields.requireFirmaId();
        TransportOrder o = orders.findById(orderId).filter(x -> firmaId.equals(x.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
        if (o.getTripId() != null || o.getStatus() == TransportOrder.Status.CANCELLED
                || o.getStatus() == TransportOrder.Status.INVOICED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        List<TripDto.StopInput> route = List.of(
                new TripDto.StopInput(null, TripStop.Kind.LOADING.name(), o.getId(), null, o.getLoadingPlace(),
                        o.getLoadingCountry(), startOfDay(o.getLoadingDate()), null, null),
                new TripDto.StopInput(null, TripStop.Kind.UNLOADING.name(), o.getId(), null, o.getUnloadingPlace(),
                        o.getUnloadingCountry(), startOfDay(o.getUnloadingDate()), null, null));
        Instant start = request.plannedStart() != null ? request.plannedStart() : startOfDay(o.getLoadingDate());
        return create(new TripDto.Input(null, request.vehicleId(), request.trailerId(), request.mainDriverId(),
                request.secondDriverId(), null, start, startOfDay(o.getUnloadingDate()), null, null, null, null,
                List.of(o.getId()), route, null));
    }

    /** Takes an order off its trip (cancelled order): stops keep their place but no longer name it. */
    void detachOrder(TransportOrder o) {
        if (o.getTripId() == null) {
            return;
        }
        for (TripStop s : stops.findByTripIdOrderBySeqAsc(o.getTripId())) {
            if (o.getId().equals(s.getOrderId())) {
                s.setOrderId(null);
                stops.save(s);
            }
        }
        o.setTripId(null);
    }

    // --- the full replacement ------------------------------------------------------------------------------

    private record Crew(Driver main, Driver second) {
    }

    private void save(Trip trip, TripDto.Input in) {
        Map<String, String> errors = new LinkedHashMap<>();
        Long firmaId = trip.getFirmaId();
        String number = Numbers.typed(in.number());
        if (number != null) {
            if (!Numbers.valid(number)) {
                errors.put("number", ErrorCodes.FIELD_INVALID_FORMAT);
            } else if (trips.findByNumber(number).filter(t -> !t.getId().equals(trip.getId())).isPresent()) {
                errors.put("number", ErrorCodes.TRIP_NUMBER_TAKEN);
            }
        }
        Vehicle vehicle = null;
        if (in.vehicleId() == null) {
            errors.put("vehicleId", ErrorCodes.FIELD_REQUIRED);
        } else {
            vehicle = vehicles.findByIdAndDeletedFalse(in.vehicleId())
                    .filter(v -> firmaId.equals(v.getFirmaId()) && !v.getKind().isTrailer()).orElse(null);
            if (vehicle == null) {
                errors.put("vehicleId", ErrorCodes.FIELD_INVALID_VALUE);
            }
        }
        if (in.trailerId() != null && vehicles.findByIdAndDeletedFalse(in.trailerId())
                .filter(v -> firmaId.equals(v.getFirmaId()) && v.getKind().isTrailer()).isEmpty()) {
            errors.put("trailerId", ErrorCodes.FIELD_INVALID_VALUE);
        }
        Driver main = driver("mainDriverId", in.mainDriverId(), firmaId, errors);
        Driver second = driver("secondDriverId", in.secondDriverId(), firmaId, errors);
        if (in.secondDriverId() != null && (in.mainDriverId() == null
                || in.secondDriverId().equals(in.mainDriverId()))) {
            errors.put("secondDriverId", ErrorCodes.FIELD_INVALID_VALUE);
        }
        Trip.Status status = InputFields.parseEnum(Trip.Status.class, "status", in.status(), false, errors);
        if (status == null) {
            status = Trip.Status.PLANNED;
        }
        order("plannedEnd", in.plannedStart(), in.plannedEnd(), errors);
        order("actualEnd", in.actualStart(), in.actualEnd(), errors);
        List<TransportOrder> requested = orders(trip, in.orderIds(), firmaId, errors);
        Set<Long> requestedIds = requested.stream().map(TransportOrder::getId).collect(Collectors.toSet());
        Map<Long, TripStop> existingStops = trip.getId() == null ? Map.of()
                : stops.findByTripIdOrderBySeqAsc(trip.getId()).stream()
                .collect(Collectors.toMap(TripStop::getId, Function.identity()));
        List<TripStop> route = route(trip, in.stops(), requestedIds, existingStops, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        // A cancelled trip lets its orders go; its stops stay as a record but no longer name them.
        List<TransportOrder> carried = status == Trip.Status.CANCELLED ? List.of() : requested;
        if (status == Trip.Status.CANCELLED) {
            route.forEach(s -> s.setOrderId(null));
        }

        if (number == null && trip.getNumber() == null) {
            String prefix = Numbers.yearPrefix(Numbers.TRIP_PREFIX, LocalDate.now(ZoneOffset.UTC).getYear());
            number = Numbers.next(prefix, trips.numbersLike(prefix + "%"));
        }
        if (number != null) {
            trip.setNumber(number);
        }
        trip.setVehicleId(vehicle.getId());
        trip.setTrailerId(in.trailerId());
        trip.setStatus(status);
        trip.setPlannedStart(in.plannedStart());
        trip.setPlannedEnd(in.plannedEnd());
        trip.setActualStart(in.actualStart());
        trip.setActualEnd(in.actualEnd());
        if (status == Trip.Status.RUNNING && trip.getActualStart() == null) {
            trip.setActualStart(Instant.now());
        }
        if (status == Trip.Status.DONE && trip.getActualEnd() == null) {
            trip.setActualEnd(Instant.now());
        }
        trip.setKmPlanned(in.kmPlanned());
        trip.setKmActual(in.kmActual());
        trip.setNotes(InputFields.blankToNull(in.notes()));
        try {
            trips.saveAndFlush(trip);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.validation(Map.of("number", ErrorCodes.TRIP_NUMBER_TAKEN));
        }
        saveCrew(trip, new Crew(main, second));
        saveOrders(trip, carried);
        saveRoute(trip, route, existingStops);
    }

    private Driver driver(String field, Long id, Long firmaId, Map<String, String> errors) {
        if (id == null) {
            return null;
        }
        Driver d = drivers.findByIdAndDeletedFalse(id).filter(x -> firmaId.equals(x.getFirmaId())).orElse(null);
        if (d == null) {
            errors.put(field, ErrorCodes.FIELD_INVALID_VALUE);
        }
        return d;
    }

    private List<TransportOrder> orders(Trip trip, List<Long> ids, Long firmaId, Map<String, String> errors) {
        List<TransportOrder> result = new ArrayList<>();
        if (ids == null) {
            return result;
        }
        for (Long id : new LinkedHashSet<>(ids)) {
            TransportOrder o = id == null ? null
                    : orders.findById(id).filter(x -> firmaId.equals(x.getFirmaId())).orElse(null);
            boolean free = o != null && (o.getTripId() == null || o.getTripId().equals(trip.getId()))
                    && o.getStatus() != TransportOrder.Status.CANCELLED
                    && (o.getStatus() != TransportOrder.Status.INVOICED || Objects.equals(o.getTripId(), trip.getId()));
            if (!free) {
                errors.put("orderIds", ErrorCodes.FIELD_INVALID_VALUE);
                continue;
            }
            result.add(o);
        }
        return result;
    }

    private List<TripStop> route(Trip trip, List<TripDto.StopInput> input, Set<Long> carriedIds,
                                 Map<Long, TripStop> existing, Map<String, String> errors) {
        List<TripStop> result = new ArrayList<>();
        if (input == null) {
            return result;
        }
        for (int i = 0; i < input.size(); i++) {
            TripDto.StopInput in = input.get(i);
            String at = "stops[" + i + "].";
            if (in == null) {
                errors.put("stops[" + i + "]", ErrorCodes.FIELD_REQUIRED);
                continue;
            }
            TripStop stop;
            if (in.id() != null) {
                stop = existing.get(in.id());
                if (stop == null) {
                    errors.put(at + "id", ErrorCodes.FIELD_INVALID_VALUE);
                    continue;
                }
            } else {
                stop = new TripStop();
                stop.setFirmaId(trip.getFirmaId());
            }
            TripStop.Kind kind = InputFields.parseEnum(TripStop.Kind.class, at + "kind", in.kind(), true, errors);
            TripStop.Status status = InputFields.parseEnum(TripStop.Status.class, at + "status", in.status(), false,
                    errors);
            String address = InputFields.blankToNull(in.address());
            if (address == null) {
                errors.put(at + "address", ErrorCodes.FIELD_REQUIRED);
            }
            String country = InputFields.country(at + "country", in.country(), errors);
            if (country == null && !errors.containsKey(at + "country")) {
                errors.put(at + "country", ErrorCodes.FIELD_REQUIRED);
            }
            if (in.orderId() != null && !carriedIds.contains(in.orderId())) {
                errors.put(at + "orderId", ErrorCodes.FIELD_INVALID_VALUE);
            }
            stop.setSeq(i + 1);
            stop.setKind(kind);
            stop.setOrderId(in.orderId());
            stop.setName(InputFields.blankToNull(in.name()));
            stop.setAddress(address);
            stop.setCountry(country);
            stop.setPlannedAt(in.plannedAt());
            stop.setActualAt(in.actualAt());
            stop.setStatus(status == null ? TripStop.Status.PLANNED : status);
            result.add(stop);
        }
        return result;
    }

    /** The crew is small: delete and insert (flushed in between, the unique keys are per role and per driver). */
    private void saveCrew(Trip trip, Crew c) {
        List<TripDriver> old = crew.findByTripId(trip.getId());
        if (!old.isEmpty()) {
            crew.deleteAll(old);
            crew.flush();
        }
        if (c.main() != null) {
            crew.save(member(trip, c.main(), TripDriver.Role.MAIN));
        }
        if (c.second() != null) {
            crew.save(member(trip, c.second(), TripDriver.Role.SECOND));
        }
    }

    private static TripDriver member(Trip trip, Driver driver, TripDriver.Role role) {
        TripDriver m = new TripDriver();
        m.setFirmaId(trip.getFirmaId());
        m.setTripId(trip.getId());
        m.setDriverId(driver.getId());
        m.setRole(role);
        return m;
    }

    private void saveOrders(Trip trip, List<TransportOrder> carried) {
        Set<Long> keep = carried.stream().map(TransportOrder::getId).collect(Collectors.toSet());
        for (TransportOrder o : orders.findByTripIdOrderByNumberAsc(trip.getId())) {
            if (!keep.contains(o.getId())) {
                release(o);
            }
        }
        for (TransportOrder o : carried) {
            o.setTripId(trip.getId());
            o.setStatus(follow(o.getStatus(), trip.getStatus()));
            orders.save(o);
        }
        orders.flush();
    }

    /** An order taken off a trip goes back to NEW (CANCELLED and INVOICED stay). */
    private void release(TransportOrder o) {
        o.setTripId(null);
        if (o.getStatus() != TransportOrder.Status.CANCELLED && o.getStatus() != TransportOrder.Status.INVOICED) {
            o.setStatus(TransportOrder.Status.NEW);
        }
        orders.save(o);
    }

    /** The order status that follows from the trip status. */
    static TransportOrder.Status follow(TransportOrder.Status current, Trip.Status trip) {
        if (current == TransportOrder.Status.CANCELLED || current == TransportOrder.Status.INVOICED) {
            return current;
        }
        return switch (trip) {
            case PLANNED -> TransportOrder.Status.PLANNED;
            case RUNNING -> TransportOrder.Status.IN_PROGRESS;
            case DONE -> TransportOrder.Status.DONE;
            case CANCELLED -> TransportOrder.Status.NEW;
        };
    }

    private void saveRoute(Trip trip, List<TripStop> route, Map<Long, TripStop> existing) {
        Set<Long> kept = route.stream().map(TripStop::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<TripStop> gone = existing.values().stream().filter(s -> !kept.contains(s.getId())).toList();
        if (!gone.isEmpty()) {
            stops.deleteAll(gone);
            stops.flush();
        }
        for (TripStop s : route) {
            s.setTripId(trip.getId());
        }
        stops.saveAll(route);
        stops.flush();
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private Trip load(Long id) {
        Long firmaId = InputFields.requireFirmaId();
        return trips.findById(id).filter(t -> firmaId.equals(t.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    TripDto toDto(Trip trip) {
        return toDtos(List.of(trip)).getFirst();
    }

    private List<TripDto> toDtos(List<Trip> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(Trip::getId).toList();
        Set<Long> vehicleIds = new LinkedHashSet<>();
        list.forEach(t -> {
            vehicleIds.add(t.getVehicleId());
            if (t.getTrailerId() != null) {
                vehicleIds.add(t.getTrailerId());
            }
        });
        Map<Long, Vehicle> vehicleOf = byId(vehicles.findAllById(vehicleIds), Vehicle::getId);
        List<TripDriver> members = crew.findByTripIdIn(ids);
        Map<Long, Driver> driverOf = byId(drivers.findAllById(members.stream().map(TripDriver::getDriverId)
                .collect(Collectors.toSet())), Driver::getId);
        List<TransportOrder> carried = orders.findByTripIdIn(ids);
        Map<Long, Contractor> customerOf = byId(contractors.findAllById(carried.stream()
                .map(TransportOrder::getContractorId).collect(Collectors.toSet())), Contractor::getId);
        Map<Long, List<TripStop>> stopsOf = new HashMap<>();
        for (TripStop s : stops.findByTripIdInOrderBySeqAsc(ids)) {
            stopsOf.computeIfAbsent(s.getTripId(), k -> new ArrayList<>()).add(s);
        }
        List<TripDto> result = new ArrayList<>();
        for (Trip t : list) {
            List<TripDto.CrewMember> team = members.stream().filter(m -> m.getTripId().equals(t.getId()))
                    .sorted((a, b) -> a.getRole().compareTo(b.getRole()))
                    .map(m -> {
                        Driver d = driverOf.get(m.getDriverId());
                        return new TripDto.CrewMember(m.getDriverId(), d == null ? null : d.getFullName(), m.getRole());
                    }).toList();
            List<TripDto.OrderRef> load = carried.stream().filter(o -> t.getId().equals(o.getTripId()))
                    .sorted((a, b) -> a.getNumber().compareTo(b.getNumber()))
                    .map(o -> new TripDto.OrderRef(o.getId(), o.getNumber(),
                            ContractorDto.Ref.of(customerOf.get(o.getContractorId())), o.getPrice(), o.getCurrency()))
                    .toList();
            List<TripDto.Stop> route = stopsOf.getOrDefault(t.getId(), List.of()).stream().map(TripDto.Stop::of)
                    .toList();
            result.add(new TripDto(t.getId(), t.getNumber(), t.getStatus(), ref(vehicleOf.get(t.getVehicleId())),
                    t.getTrailerId() == null ? null : ref(vehicleOf.get(t.getTrailerId())), team,
                    t.getPlannedStart(), t.getPlannedEnd(), t.getActualStart(), t.getActualEnd(), t.getKmPlanned(),
                    t.getKmActual(), load, route, t.getNotes(), t.getCreatedAt(), t.getUpdatedAt()));
        }
        return result;
    }

    private static TripDto.VehicleRef ref(Vehicle v) {
        return v == null ? null : new TripDto.VehicleRef(v.getId(), v.getPlate());
    }

    private static <T> Map<Long, T> byId(Collection<T> items, Function<T, Long> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }

    private static void order(String field, Instant from, Instant to, Map<String, String> errors) {
        if (from != null && to != null && to.isBefore(from)) {
            errors.put(field, ErrorCodes.FIELD_INVALID_VALUE);
        }
    }

    static void pageChecks(int page, int size, Map<String, String> errors) {
        if (page < 0) {
            errors.put("page", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (size < 1 || size > 200) {
            errors.put("size", ErrorCodes.FIELD_INVALID_VALUE);
        }
    }

    /** A lower-case LIKE pattern for {@code q}, or null. */
    static String like(String q) {
        return q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
    }

    private static Instant startOfDay(LocalDate day) {
        return day == null ? null : day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}

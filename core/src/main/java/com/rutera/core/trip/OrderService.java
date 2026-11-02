package com.rutera.core.trip;

import com.rutera.common.domain.Contractor;
import com.rutera.common.domain.TransportOrder;
import com.rutera.common.domain.Trip;
import com.rutera.common.repository.CmrDocumentRepository;
import com.rutera.common.repository.ContractorRepository;
import com.rutera.common.repository.TransportOrderRepository;
import com.rutera.common.repository.TripRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Customer orders of the firma ({@code /app/orders}). The status is NEW without a trip and follows the trip
 * otherwise (see {@link TripService}); {@code cancelled} in the input is the only way to set CANCELLED by hand.
 * An order on a trip cannot be deleted; take it off the trip or cancel it first.
 */
@Service
public class OrderService {

    public static final String MODULE = "trip";

    private static final Pattern CURRENCY_SHAPE = Pattern.compile("[A-Z]{3}");

    private final TransportOrderRepository orders;
    private final ContractorRepository contractors;
    private final TripRepository trips;
    private final ContractorService contractorService;
    private final TripService tripService;
    private final CmrDocumentRepository cmrs;
    private final AuditService audit;

    public OrderService(TransportOrderRepository orders, ContractorRepository contractors, TripRepository trips,
                        ContractorService contractorService, TripService tripService, CmrDocumentRepository cmrs,
                        AuditService audit) {
        this.cmrs = cmrs;
        this.orders = orders;
        this.contractors = contractors;
        this.trips = trips;
        this.contractorService = contractorService;
        this.tripService = tripService;
        this.audit = audit;
    }

    @RequiresPrivilege(Privileges.TRIP_ORDER_VIEW)
    @Transactional(readOnly = true)
    public OrderDto.Page list(String status, Long customerId, String q, LocalDate from, LocalDate to, int page,
                              int size) {
        InputFields.requireFirmaId();
        Map<String, String> errors = new LinkedHashMap<>();
        TransportOrder.Status s = InputFields.parseEnum(TransportOrder.Status.class, "status", status, false, errors);
        TripService.pageChecks(page, size, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        org.springframework.data.domain.Page<TransportOrder> result = orders.search(s, customerId, from, to,
                TripService.like(q), PageRequest.of(page, size));
        return new OrderDto.Page(toDtos(result.getContent()), result.getTotalElements());
    }

    @RequiresPrivilege(Privileges.TRIP_ORDER_VIEW)
    @Transactional(readOnly = true)
    public OrderDto get(Long id) {
        return toDtos(List.of(load(id))).getFirst();
    }

    @RequiresPrivilege(Privileges.TRIP_ORDER_MANAGE)
    @Transactional
    public OrderDto create(OrderDto.Input input) {
        TransportOrder o = new TransportOrder();
        o.setFirmaId(InputFields.requireFirmaId());
        apply(o, input);
        audit.record(AuditEvent.of(MODULE, "order_created", "Added order " + o.getNumber())
                .entity("transport_order", o.getId()));
        return toDtos(List.of(o)).getFirst();
    }

    @RequiresPrivilege(Privileges.TRIP_ORDER_MANAGE)
    @Transactional
    public OrderDto update(Long id, OrderDto.Input input) {
        TransportOrder o = load(id);
        apply(o, input);
        audit.record(AuditEvent.of(MODULE, "order_updated", "Updated order " + o.getNumber())
                .entity("transport_order", o.getId())
                .detail("status", o.getStatus().name()));
        return toDtos(List.of(o)).getFirst();
    }

    /** Physical delete; an order on a trip or already invoiced is a 409. */
    @RequiresPrivilege(Privileges.TRIP_ORDER_MANAGE)
    @Transactional
    public void delete(Long id) {
        TransportOrder o = load(id);
        if (o.getTripId() != null || o.getStatus() == TransportOrder.Status.INVOICED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        cmrs.detachOrder(o.getId());
        orders.delete(o);
        audit.record(AuditEvent.of(MODULE, "order_deleted", "Deleted order " + o.getNumber())
                .entity("transport_order", o.getId()));
    }

    /** A trip for this order in one step (privileges checked by {@link TripService#createForOrder}). */
    public TripDto createTrip(Long id, OrderDto.TripRequest request) {
        return tripService.createForOrder(id, request);
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private TransportOrder load(Long id) {
        Long firmaId = InputFields.requireFirmaId();
        return orders.findById(id).filter(o -> firmaId.equals(o.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private void apply(TransportOrder o, OrderDto.Input in) {
        Map<String, String> errors = new LinkedHashMap<>();
        String number = Numbers.typed(in.number());
        if (number != null) {
            if (!Numbers.valid(number)) {
                errors.put("number", ErrorCodes.FIELD_INVALID_FORMAT);
            } else if (orders.findByNumber(number).filter(x -> !x.getId().equals(o.getId())).isPresent()) {
                errors.put("number", ErrorCodes.ORDER_NUMBER_TAKEN);
            }
        }
        Contractor customer = contractorService.findUsable(in.customerId());
        if (customer == null) {
            errors.put("customerId", ErrorCodes.FIELD_INVALID_VALUE);
        }
        String currency = InputFields.blankToNull(in.currency());
        if (currency != null) {
            currency = currency.toUpperCase(Locale.ROOT);
            if (!CURRENCY_SHAPE.matcher(currency).matches()) {
                errors.put("currency", ErrorCodes.FIELD_INVALID_FORMAT);
            }
        } else if (in.price() != null) {
            errors.put("currency", ErrorCodes.FIELD_REQUIRED);
        }
        String loadingCountry = InputFields.country("loadingCountry", in.loadingCountry(), errors);
        String unloadingCountry = InputFields.country("unloadingCountry", in.unloadingCountry(), errors);
        InputFields.dateOrder("unloadingDate", in.loadingDate(), in.unloadingDate(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }

        if (number == null && o.getNumber() == null) {
            String prefix = Numbers.yearPrefix(Numbers.ORDER_PREFIX, in.orderDate().getYear());
            number = Numbers.next(prefix, orders.numbersLike(prefix + "%"));
        }
        if (number != null) {
            o.setNumber(number);
        }
        o.setOrderDate(in.orderDate());
        o.setContractorId(customer.getId());
        o.setCustomerRef(InputFields.blankToNull(in.customerRef()));
        o.setPrice(money(in.price()));
        o.setCurrency(in.price() == null ? null : currency);
        o.setCargo(InputFields.blankToNull(in.cargo()));
        o.setWeightKg(in.weightKg());
        o.setLoadingPlace(in.loadingPlace().strip());
        o.setLoadingCountry(loadingCountry);
        o.setLoadingDate(in.loadingDate());
        o.setUnloadingPlace(in.unloadingPlace().strip());
        o.setUnloadingCountry(unloadingCountry);
        o.setUnloadingDate(in.unloadingDate());
        o.setNotes(InputFields.blankToNull(in.notes()));
        if (Objects.equals(in.cancelled(), Boolean.TRUE)) {
            if (o.getInvoiceId() != null) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
            }
            if (o.getStatus() != TransportOrder.Status.CANCELLED) {
                tripService.detachOrder(o);
                o.setStatus(TransportOrder.Status.CANCELLED);
            }
        } else if (o.getStatus() == TransportOrder.Status.CANCELLED) {
            o.setStatus(TransportOrder.Status.NEW);
        }
        try {
            orders.saveAndFlush(o);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.validation(Map.of("number", ErrorCodes.ORDER_NUMBER_TAKEN));
        }
    }

    private List<OrderDto> toDtos(List<TransportOrder> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Contractor> customerOf = byId(contractors.findAllById(list.stream()
                .map(TransportOrder::getContractorId).collect(Collectors.toSet())), Contractor::getId);
        Map<Long, Trip> tripOf = byId(trips.findAllById(list.stream().map(TransportOrder::getTripId)
                .filter(Objects::nonNull).collect(Collectors.toSet())), Trip::getId);
        return list.stream().map(o -> OrderDto.of(o, customerOf.get(o.getContractorId()),
                o.getTripId() == null ? null : tripOf.get(o.getTripId()))).toList();
    }

    private static <T> Map<Long, T> byId(Collection<T> items, Function<T, Long> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }

    /** DECIMAL(14,2): the same scale as read back, so 4200.5 and 4200.50 compare equal. */
    static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}

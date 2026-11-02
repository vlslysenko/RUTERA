package com.rutera.core.fuel;

import com.rutera.common.domain.Driver;
import com.rutera.common.domain.FuelCard;
import com.rutera.common.domain.FuelProvider;
import com.rutera.common.domain.FuelTransaction;
import com.rutera.common.domain.Trip;
import com.rutera.common.domain.Vehicle;
import com.rutera.common.repository.DriverRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.repository.FuelCardRepository;
import com.rutera.common.repository.FuelTransactionRepository;
import com.rutera.common.repository.TripRepository;
import com.rutera.common.repository.VehicleRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.fuel.read.FuelValues;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fuel transactions ({@code /app/fuel/transactions}) and fuel cards ({@code /app/fuel/cards}). Imported transactions
 * keep their values; only their assignment (vehicle, driver, trip, notes) changes by hand. Transactions entered by
 * hand (source MANUAL) can be changed fully.
 */
@Service
public class FuelService {

    public static final String MODULE = "fuel";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final FuelTransactionRepository transactions;
    private final FuelCardRepository cards;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final TripRepository trips;
    private final FirmaRepository firmas;
    private final FuelMatching matching;
    private final AuditService audit;

    public FuelService(FuelTransactionRepository transactions, FuelCardRepository cards, VehicleRepository vehicles,
                       DriverRepository drivers, TripRepository trips, FirmaRepository firmas, FuelMatching matching,
                       AuditService audit) {
        this.transactions = transactions;
        this.cards = cards;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.trips = trips;
        this.firmas = firmas;
        this.matching = matching;
        this.audit = audit;
    }

    // --- transactions --------------------------------------------------------------------------------------

    @RequiresPrivilege(Privileges.FUEL_TRANSACTION_VIEW)
    @Transactional(readOnly = true)
    public FuelDto.Page list(LocalDate from, LocalDate to, Long vehicleId, Long driverId, Long cardId, Long tripId,
                             Long importId, String product, String country, boolean unmatched, String q, int page,
                             int size) {
        Long firmaId = InputFields.requireFirmaId();
        Map<String, String> errors = new LinkedHashMap<>();
        FuelTransaction.Product p = InputFields.parseEnum(FuelTransaction.Product.class, "product", product, false,
                errors);
        if (page < 0) {
            errors.put("page", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (size < 1 || size > 500) {
            errors.put("size", ErrorCodes.FIELD_INVALID_VALUE);
        }
        InputFields.dateOrder("to", from, to, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        var zone = FuelImportService.zone(firmas.findById(firmaId).orElseThrow());
        Instant start = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        String like = q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
        String c = country == null || country.isBlank() ? null : country.strip().toUpperCase(Locale.ROOT);
        List<FuelTransaction> all = transactions.search(start, end, vehicleId, driverId, cardId, tripId, importId, p,
                c, unmatched, like);
        Map<String, BigDecimal[]> sums = new TreeMap<>();
        Map<String, BigDecimal> quantities = new TreeMap<>();
        for (FuelTransaction t : all) {
            BigDecimal[] s = sums.computeIfAbsent(t.getCurrency(), k -> new BigDecimal[] {ZERO, ZERO, ZERO});
            s[0] = s[0].add(t.getNet() == null ? ZERO : t.getNet());
            s[1] = s[1].add(t.getVat() == null ? ZERO : t.getVat());
            s[2] = s[2].add(t.getGross());
            if (t.getQuantity() != null) {
                quantities.merge(t.getProduct() + "|" + (t.getUnit() == null ? "" : t.getUnit().name()),
                        t.getQuantity(), BigDecimal::add);
            }
        }
        int fromIndex = Math.min(all.size(), page * size);
        List<FuelTransaction> slice = all.subList(fromIndex, Math.min(all.size(), fromIndex + size));
        return new FuelDto.Page(toDtos(slice), all.size(),
                sums.entrySet().stream().map(e -> new FuelDto.Total(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[2])).toList(),
                quantities.entrySet().stream().map(e -> {
                    String[] k = e.getKey().split("\\|", -1);
                    return new FuelDto.Quantity(FuelTransaction.Product.valueOf(k[0]),
                            k[1].isEmpty() ? null : FuelTransaction.Unit.valueOf(k[1]), e.getValue());
                }).toList());
    }

    @RequiresPrivilege(Privileges.FUEL_TRANSACTION_VIEW)
    @Transactional(readOnly = true)
    public FuelDto.Transaction get(Long id) {
        return toDtos(List.of(load(id))).getFirst();
    }

    @RequiresPrivilege(Privileges.FUEL_IMPORT_MANAGE)
    @Transactional
    public FuelDto.Transaction create(FuelDto.TransactionInput input) {
        FuelTransaction t = new FuelTransaction();
        t.setFirmaId(InputFields.requireFirmaId());
        t.setSource(FuelTransaction.Source.MANUAL);
        t.setExternalId("M:" + UUID.randomUUID());
        save(t, input);
        audit.record(AuditEvent.of(MODULE, "fuel_transaction_created", "Entered a fuel transaction")
                .entity("fuel_transaction", t.getId()));
        return get(t.getId());
    }

    /** Full replacement of a transaction entered by hand; 409 for imported ones (use the assignment). */
    @RequiresPrivilege(Privileges.FUEL_IMPORT_MANAGE)
    @Transactional
    public FuelDto.Transaction update(Long id, FuelDto.TransactionInput input) {
        FuelTransaction t = load(id);
        if (t.getSource() != FuelTransaction.Source.MANUAL) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        save(t, input);
        audit.record(AuditEvent.of(MODULE, "fuel_transaction_updated", "Updated a fuel transaction")
                .entity("fuel_transaction", t.getId()));
        return get(t.getId());
    }

    /** Vehicle, driver, trip and notes of any transaction, set by hand (match MANUAL). */
    @RequiresPrivilege(Privileges.FUEL_IMPORT_MANAGE)
    @Transactional
    public FuelDto.Transaction assign(Long id, FuelDto.AssignInput input) {
        FuelTransaction t = load(id);
        Map<String, String> errors = new LinkedHashMap<>();
        Long vehicleId = vehicle(input.vehicleId(), errors);
        Long driverId = driver(input.driverId(), errors);
        Long tripId = trip(input.tripId(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        t.setVehicleId(vehicleId);
        t.setDriverId(driverId);
        t.setTripId(tripId);
        t.setNotes(InputFields.blankToNull(input.notes()));
        t.setMatchKind(vehicleId == null && driverId == null ? FuelTransaction.Match.NONE
                : FuelTransaction.Match.MANUAL);
        transactions.save(t);
        audit.record(AuditEvent.of(MODULE, "fuel_transaction_assigned", "Assigned a fuel transaction")
                .entity("fuel_transaction", t.getId()).detail("vehicleId", vehicleId));
        return get(t.getId());
    }

    @RequiresPrivilege(Privileges.FUEL_IMPORT_MANAGE)
    @Transactional
    public void delete(Long id) {
        FuelTransaction t = load(id);
        transactions.delete(t);
        audit.record(AuditEvent.of(MODULE, "fuel_transaction_deleted", "Deleted a fuel transaction")
                .entity("fuel_transaction", t.getId()).detail("source", t.getSource().name()));
    }

    private void save(FuelTransaction t, FuelDto.TransactionInput in) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (in.at() == null) {
            errors.put("at", ErrorCodes.FIELD_REQUIRED);
        }
        FuelCard card = null;
        if (in.cardId() != null) {
            card = cards.findById(in.cardId()).filter(x -> t.getFirmaId().equals(x.getFirmaId())).orElse(null);
            if (card == null) {
                errors.put("cardId", ErrorCodes.FIELD_INVALID_VALUE);
            }
        }
        Long vehicleId = vehicle(in.vehicleId(), errors);
        Long driverId = driver(in.driverId(), errors);
        Long tripId = trip(in.tripId(), errors);
        FuelTransaction.Product product = InputFields.parseEnum(FuelTransaction.Product.class, "product",
                in.product(), true, errors);
        FuelTransaction.Unit unit = InputFields.parseEnum(FuelTransaction.Unit.class, "unit", in.unit(), false,
                errors);
        String country = null;
        if (in.country() != null && !in.country().isBlank()) {
            country = FuelValues.country(in.country());
            if (country == null) {
                errors.put("country", ErrorCodes.FIELD_INVALID_VALUE);
            }
        }
        String currency = in.currency() == null ? null : FuelValues.currency(in.currency());
        if (in.currency() == null || in.currency().isBlank()) {
            errors.put("currency", ErrorCodes.FIELD_REQUIRED);
        } else if (currency == null) {
            errors.put("currency", ErrorCodes.FIELD_INVALID_FORMAT);
        }
        BigDecimal net = money(in.net());
        BigDecimal vat = money(in.vat());
        BigDecimal gross = money(in.gross());
        if (gross == null && net != null) {
            gross = net.add(vat == null ? ZERO : vat);
        }
        if (vat == null && gross != null && net != null) {
            vat = gross.subtract(net);
        }
        if (gross == null) {
            errors.put("gross", ErrorCodes.FIELD_REQUIRED);
        }
        if (in.odometerKm() != null && (in.odometerKm() < 0 || in.odometerKm() > 9_999_999)) {
            errors.put("odometerKm", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        t.setAt(in.at());
        t.setProvider(card == null ? FuelProvider.OTHER : card.getProvider());
        t.setCardId(card == null ? null : card.getId());
        if (vehicleId == null && card != null) {
            vehicleId = card.getVehicleId();
        }
        if (driverId == null && card != null) {
            driverId = card.getDriverId();
        }
        if (tripId == null && vehicleId != null) {
            Trip trip = matching.context(List.of(in.at())).trip(vehicleId, in.at());
            tripId = trip == null ? null : trip.getId();
        }
        t.setVehicleId(vehicleId);
        t.setDriverId(driverId);
        t.setTripId(tripId);
        t.setStation(InputFields.blankToNull(in.station()));
        t.setCountry(country);
        t.setProduct(product);
        t.setProductRaw(InputFields.blankToNull(in.productRaw()));
        t.setQuantity(in.quantity() == null ? null : in.quantity().setScale(3, RoundingMode.HALF_UP));
        t.setUnit(unit != null || in.quantity() == null ? unit : switch (product) {
            case DIESEL, PETROL, ADBLUE -> FuelTransaction.Unit.L;
            case LNG, CNG -> FuelTransaction.Unit.KG;
            default -> null;
        });
        t.setUnitPrice(in.unitPrice() == null ? null : in.unitPrice().setScale(4, RoundingMode.HALF_UP));
        t.setNet(net);
        t.setVat(vat);
        t.setGross(gross);
        t.setCurrency(currency);
        t.setOdometerKm(in.odometerKm());
        t.setNotes(InputFields.blankToNull(in.notes()));
        t.setMatchKind(vehicleId == null && driverId == null ? FuelTransaction.Match.NONE
                : FuelTransaction.Match.MANUAL);
        transactions.saveAndFlush(t);
    }

    // --- cards ---------------------------------------------------------------------------------------------

    @RequiresPrivilege(Privileges.FUEL_TRANSACTION_VIEW)
    @Transactional(readOnly = true)
    public List<FuelDto.Card> cards() {
        return toCards(cards.findAllByOrderByProviderAscCardNumberMaskedAsc());
    }

    @RequiresPrivilege(Privileges.FUEL_IMPORT_MANAGE)
    @Transactional
    public FuelDto.Card createCard(FuelDto.CardInput input) {
        FuelCard c = new FuelCard();
        c.setFirmaId(InputFields.requireFirmaId());
        saveCard(c, input);
        audit.record(AuditEvent.of(MODULE, "fuel_card_created", "Added fuel card " + c.getCardNumberMasked())
                .entity("fuel_card", c.getId()));
        return toCards(List.of(c)).getFirst();
    }

    /** A new vehicle or driver of the card is also set on its transactions that have none yet. */
    @RequiresPrivilege(Privileges.FUEL_IMPORT_MANAGE)
    @Transactional
    public FuelDto.Card updateCard(Long id, FuelDto.CardInput input) {
        FuelCard c = loadCard(id);
        saveCard(c, input);
        List<FuelTransaction> open = transactions.search(null, null, null, null, c.getId(), null, null, null, null,
                false, null).stream().filter(t -> t.getMatchKind() != FuelTransaction.Match.MANUAL
                && (t.getVehicleId() == null || t.getDriverId() == null)).toList();
        if (!open.isEmpty() && (c.getVehicleId() != null || c.getDriverId() != null)) {
            FuelMatching.Context ctx = matching.context(open.stream().map(FuelTransaction::getAt).toList());
            for (FuelTransaction t : open) {
                if (t.getVehicleId() == null) {
                    ctx.assign(t, c, null);
                } else if (t.getDriverId() == null) {
                    t.setDriverId(c.getDriverId());
                }
            }
            transactions.saveAll(open);
        }
        audit.record(AuditEvent.of(MODULE, "fuel_card_updated", "Updated fuel card " + c.getCardNumberMasked())
                .entity("fuel_card", c.getId()));
        return toCards(List.of(c)).getFirst();
    }

    /** The card goes; its transactions stay without a card. */
    @RequiresPrivilege(Privileges.FUEL_IMPORT_MANAGE)
    @Transactional
    public void deleteCard(Long id) {
        FuelCard c = loadCard(id);
        transactions.detachCard(c.getId());
        cards.delete(c);
        audit.record(AuditEvent.of(MODULE, "fuel_card_deleted", "Deleted fuel card " + c.getCardNumberMasked())
                .entity("fuel_card", c.getId()));
    }

    private void saveCard(FuelCard c, FuelDto.CardInput in) {
        Map<String, String> errors = new LinkedHashMap<>();
        FuelProvider provider = InputFields.parseEnum(FuelProvider.class, "provider", in.provider(), true, errors);
        String number = FuelValues.cardNumber(in.cardNumber());
        if (number.isEmpty() && c.getId() == null) {
            errors.put("cardNumber", ErrorCodes.FIELD_REQUIRED);
        } else if (!number.isEmpty() && number.replace("*", "").length() < 4) {
            errors.put("cardNumber", ErrorCodes.FIELD_INVALID_FORMAT);
        }
        String hash = null;
        if (provider != null && !number.isEmpty()) {
            hash = FuelImportService.cardHash(provider, number);
        } else if (provider != null && c.getId() != null && provider != c.getProvider()) {
            errors.put("cardNumber", ErrorCodes.FIELD_REQUIRED);
        }
        if (hash != null) {
            String h = hash;
            if (cards.findByProviderAndCardHash(provider, h).filter(x -> !x.getId().equals(c.getId())).isPresent()) {
                errors.put("cardNumber", ErrorCodes.FUEL_CARD_TAKEN);
            }
        }
        Long vehicleId = vehicle(in.vehicleId(), errors);
        Long driverId = driver(in.driverId(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        c.setProvider(provider);
        if (hash != null) {
            c.setCardHash(hash);
            c.setCardNumberMasked(FuelValues.maskCard(number));
        }
        c.setVehicleId(vehicleId);
        c.setDriverId(driverId);
        c.setValidTo(in.validTo());
        c.setActive(!Boolean.FALSE.equals(in.active()));
        c.setNotes(InputFields.blankToNull(in.notes()));
        cards.saveAndFlush(c);
    }

    private FuelCard loadCard(Long id) {
        Long firmaId = InputFields.requireFirmaId();
        return cards.findById(id).filter(c -> firmaId.equals(c.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private List<FuelDto.Card> toCards(List<FuelCard> list) {
        Map<Long, Vehicle> vehicleOf = byId(vehicles.findAllById(ids(list, FuelCard::getVehicleId)), Vehicle::getId);
        Map<Long, Driver> driverOf = byId(drivers.findAllById(ids(list, FuelCard::getDriverId)), Driver::getId);
        return list.stream().map(c -> new FuelDto.Card(c.getId(), c.getProvider(), c.getCardNumberMasked(),
                vehicleRef(vehicleOf.get(c.getVehicleId())), driverRef(driverOf.get(c.getDriverId())), c.getValidTo(),
                c.isActive(), c.getNotes(), c.getCreatedAt(), c.getUpdatedAt())).toList();
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private FuelTransaction load(Long id) {
        Long firmaId = InputFields.requireFirmaId();
        return transactions.findById(id).filter(t -> firmaId.equals(t.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private Long vehicle(Long id, Map<String, String> errors) {
        if (id == null) {
            return null;
        }
        Long firmaId = InputFields.requireFirmaId();
        if (vehicles.findByIdAndDeletedFalse(id).filter(v -> firmaId.equals(v.getFirmaId())).isEmpty()) {
            errors.put("vehicleId", ErrorCodes.FIELD_INVALID_VALUE);
            return null;
        }
        return id;
    }

    private Long driver(Long id, Map<String, String> errors) {
        if (id == null) {
            return null;
        }
        Long firmaId = InputFields.requireFirmaId();
        if (drivers.findById(id).filter(d -> firmaId.equals(d.getFirmaId()) && !d.isDeleted()).isEmpty()) {
            errors.put("driverId", ErrorCodes.FIELD_INVALID_VALUE);
            return null;
        }
        return id;
    }

    private Long trip(Long id, Map<String, String> errors) {
        if (id == null) {
            return null;
        }
        Long firmaId = InputFields.requireFirmaId();
        if (trips.findById(id).filter(t -> firmaId.equals(t.getFirmaId())).isEmpty()) {
            errors.put("tripId", ErrorCodes.FIELD_INVALID_VALUE);
            return null;
        }
        return id;
    }

    private List<FuelDto.Transaction> toDtos(List<FuelTransaction> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, FuelCard> cardOf = byId(cards.findAllById(ids(list, FuelTransaction::getCardId)), FuelCard::getId);
        Map<Long, Vehicle> vehicleOf = byId(vehicles.findAllById(ids(list, FuelTransaction::getVehicleId)),
                Vehicle::getId);
        Map<Long, Driver> driverOf = byId(drivers.findAllById(ids(list, FuelTransaction::getDriverId)),
                Driver::getId);
        Map<Long, Trip> tripOf = byId(trips.findAllById(ids(list, FuelTransaction::getTripId)), Trip::getId);
        return list.stream().map(t -> {
            FuelCard c = cardOf.get(t.getCardId());
            Trip trip = tripOf.get(t.getTripId());
            return new FuelDto.Transaction(t.getId(), t.getAt(), t.getProvider(), t.getExternalId(),
                    c == null ? null : new FuelDto.CardRef(c.getId(), c.getCardNumberMasked()),
                    vehicleRef(vehicleOf.get(t.getVehicleId())), driverRef(driverOf.get(t.getDriverId())),
                    trip == null ? null : new FuelDto.Ref(trip.getId(), trip.getNumber()), t.getStation(),
                    t.getCountry(), t.getProduct(), t.getProductRaw(), t.getQuantity(), t.getUnit(), t.getUnitPrice(),
                    t.getNet(), t.getVat(), t.getGross(), t.getCurrency(), t.getOdometerKm(), t.getPlateRaw(),
                    t.getMatchKind(), t.getSource(), t.getImportId(), t.getNotes(), t.getCreatedAt());
        }).toList();
    }

    private static FuelDto.Ref vehicleRef(Vehicle v) {
        return v == null ? null : new FuelDto.Ref(v.getId(), v.getPlate());
    }

    private static FuelDto.Ref driverRef(Driver d) {
        return d == null ? null : new FuelDto.Ref(d.getId(), d.getFullName());
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static <T> List<Long> ids(Collection<T> items, Function<T, Long> id) {
        return items.stream().map(id).filter(Objects::nonNull).distinct().toList();
    }

    private static <T> Map<Long, T> byId(Collection<T> items, Function<T, Long> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }
}

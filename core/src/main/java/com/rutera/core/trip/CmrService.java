package com.rutera.core.trip;

import com.rutera.common.domain.CmrDocument;
import com.rutera.common.domain.Contractor;
import com.rutera.common.domain.Firma;
import com.rutera.common.domain.StoredFile;
import com.rutera.common.domain.TransportOrder;
import com.rutera.common.domain.Trip;
import com.rutera.common.domain.TripStop;
import com.rutera.common.domain.Vehicle;
import com.rutera.common.repository.CmrDocumentRepository;
import com.rutera.common.repository.ContractorRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.repository.StoredFileRepository;
import com.rutera.common.repository.TransportOrderRepository;
import com.rutera.common.repository.TripRepository;
import com.rutera.common.repository.TripStopRepository;
import com.rutera.common.repository.VehicleRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.storage.FileStorage;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * CMR consignment notes ({@code /app/cmr}): drafts are edited freely, issuing freezes the number and stores the PDF
 * of all four copies ({@link CmrPdf}) in stored_file; a note can be cancelled, a draft deleted. Drafts can be
 * prefilled from an order (sender = customer, carrier = own firma, places, cargo, plates of the trip's vehicles) or
 * from a trip (one per order, or from its first loading and last unloading stop).
 */
@Service
public class CmrService {

    public static final String MODULE = "trip";
    static final String NUMBER_PREFIX = "CMR";
    static final String STORED_KIND = "CMR";
    static final int ISSUED_COPIES = 4;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final TypeReference<List<CmrDto.Party>> PARTIES = new TypeReference<>() {
    };
    private static final TypeReference<List<CmrDto.Line>> LINES = new TypeReference<>() {
    };

    private final CmrDocumentRepository cmrs;
    private final TransportOrderRepository orders;
    private final TripRepository trips;
    private final TripStopRepository stops;
    private final ContractorRepository contractors;
    private final VehicleRepository vehicles;
    private final FirmaRepository firmas;
    private final StoredFileRepository storedFiles;
    private final FileStorage storage;
    private final CmrPdf pdf;
    private final AuditService audit;
    private final ObjectMapper json;

    public CmrService(CmrDocumentRepository cmrs, TransportOrderRepository orders, TripRepository trips,
                      TripStopRepository stops, ContractorRepository contractors, VehicleRepository vehicles,
                      FirmaRepository firmas, StoredFileRepository storedFiles, FileStorage storage, CmrPdf pdf,
                      AuditService audit, ObjectMapper json) {
        this.cmrs = cmrs;
        this.orders = orders;
        this.trips = trips;
        this.stops = stops;
        this.contractors = contractors;
        this.vehicles = vehicles;
        this.firmas = firmas;
        this.storedFiles = storedFiles;
        this.storage = storage;
        this.pdf = pdf;
        this.audit = audit;
        this.json = json;
    }

    /** A PDF to send: file name and bytes. */
    public record Pdf(String fileName, byte[] bytes) {
    }

    // --- read ----------------------------------------------------------------------------------------------

    @RequiresPrivilege({Privileges.TRIP_ORDER_VIEW, Privileges.TRIP_CMR_MANAGE})
    @Transactional(readOnly = true)
    public CmrDto.Page list(String status, Long orderId, Long tripId, String q, LocalDate from, LocalDate to,
                            int page, int size) {
        Map<String, String> errors = new LinkedHashMap<>();
        CmrDocument.Status s = InputFields.parseEnum(CmrDocument.Status.class, "status", status, false, errors);
        if (page < 0) {
            errors.put("page", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (size < 1 || size > 200) {
            errors.put("size", ErrorCodes.FIELD_INVALID_VALUE);
        }
        InputFields.dateOrder("to", from, to, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        String like = q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
        List<CmrDocument> all = cmrs.search(s, orderId, tripId, from, to, like);
        int fromIndex = Math.min(all.size(), page * size);
        return new CmrDto.Page(toDtos(all.subList(fromIndex, Math.min(all.size(), fromIndex + size))), all.size());
    }

    @RequiresPrivilege({Privileges.TRIP_ORDER_VIEW, Privileges.TRIP_CMR_MANAGE})
    @Transactional(readOnly = true)
    public CmrDto.Cmr get(Long id) {
        return toDtos(List.of(load(id))).getFirst();
    }

    /** Draft: drawn now with a watermark; issued or cancelled: the stored file of the issue. */
    @RequiresPrivilege({Privileges.TRIP_ORDER_VIEW, Privileges.TRIP_CMR_MANAGE})
    @Transactional(readOnly = true)
    public Pdf pdf(Long id, int copies) {
        CmrDocument c = load(id);
        if (copies < 1 || copies > 4) {
            throw ApiException.validation(Map.of("copies", ErrorCodes.FIELD_INVALID_VALUE));
        }
        String name = c.getNumber().replace('/', '-') + ".pdf";
        if (c.getFileId() != null) {
            StoredFile f = storedFiles.findById(c.getFileId()).orElse(null);
            if (f != null) {
                return new Pdf(name, storage.read(f));
            }
        }
        return new Pdf(name, pdf.render(toDtos(List.of(c)).getFirst(), copies,
                c.getStatus() == CmrDocument.Status.DRAFT));
    }

    // --- write ---------------------------------------------------------------------------------------------

    @RequiresPrivilege(Privileges.TRIP_CMR_MANAGE)
    @Transactional
    public CmrDto.Cmr create(CmrDto.Input input) {
        CmrDocument c = new CmrDocument();
        c.setFirmaId(InputFields.requireFirmaId());
        save(c, input);
        audit.record(AuditEvent.of(MODULE, "cmr_created", "Added CMR " + c.getNumber()).entity("cmr", c.getId()));
        return get(c.getId());
    }

    /** Full replacement of a draft; 409 once issued or cancelled. */
    @RequiresPrivilege(Privileges.TRIP_CMR_MANAGE)
    @Transactional
    public CmrDto.Cmr update(Long id, CmrDto.Input input) {
        CmrDocument c = draft(id);
        save(c, input);
        audit.record(AuditEvent.of(MODULE, "cmr_updated", "Updated CMR " + c.getNumber()).entity("cmr", c.getId()));
        return get(c.getId());
    }

    @RequiresPrivilege(Privileges.TRIP_CMR_MANAGE)
    @Transactional
    public void delete(Long id) {
        CmrDocument c = draft(id);
        cmrs.delete(c);
        audit.record(AuditEvent.of(MODULE, "cmr_deleted", "Deleted CMR draft " + c.getNumber())
                .entity("cmr", c.getId()));
    }

    /** Freezes the draft: checks the boxes a CMR cannot do without and stores the PDF of all four copies. */
    @RequiresPrivilege(Privileges.TRIP_CMR_MANAGE)
    @Transactional
    public CmrDto.Cmr issue(Long id) {
        CmrDocument c = draft(id);
        CmrDto.Cmr dto = toDtos(List.of(c)).getFirst();
        Map<String, String> errors = new LinkedHashMap<>();
        if (dto.sender() == null || blank(dto.sender().name())) {
            errors.put("sender.name", ErrorCodes.FIELD_REQUIRED);
        }
        if ((dto.consignee() == null || blank(dto.consignee().name())) && blank(dto.deliveryPlace())) {
            errors.put("consignee.name", ErrorCodes.FIELD_REQUIRED);
        }
        if (dto.carrier() == null || blank(dto.carrier().name())) {
            errors.put("carrier.name", ErrorCodes.FIELD_REQUIRED);
        }
        if (blank(dto.loadingPlace())) {
            errors.put("loadingPlace", ErrorCodes.FIELD_REQUIRED);
        }
        if (blank(dto.deliveryPlace())) {
            errors.put("deliveryPlace", ErrorCodes.FIELD_REQUIRED);
        }
        if (dto.lines().stream().noneMatch(l -> !blank(l.nature()))) {
            errors.put("lines", ErrorCodes.FIELD_REQUIRED);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        c.setStatus(CmrDocument.Status.ISSUED);
        c.setIssuedAt(Instant.now());
        c.setIssuedBy(CurrentUser.require().id());
        byte[] bytes = pdf.render(toDtos(List.of(c)).getFirst(), ISSUED_COPIES, false);
        StoredFile f = storage.store(c.getFirmaId(), STORED_KIND, c.getNumber().replace('/', '-') + ".pdf",
                "application/pdf", bytes, sha256(bytes), CurrentUser.require().id());
        c.setFileId(f.getId());
        cmrs.save(c);
        audit.record(AuditEvent.of(MODULE, "cmr_issued", "Issued CMR " + c.getNumber()).entity("cmr", c.getId()));
        return get(c.getId());
    }

    @RequiresPrivilege(Privileges.TRIP_CMR_MANAGE)
    @Transactional
    public CmrDto.Cmr cancel(Long id) {
        CmrDocument c = load(id);
        if (c.getStatus() == CmrDocument.Status.CANCELLED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        c.setStatus(CmrDocument.Status.CANCELLED);
        cmrs.save(c);
        audit.record(AuditEvent.of(MODULE, "cmr_cancelled", "Cancelled CMR " + c.getNumber())
                .entity("cmr", c.getId()));
        return get(c.getId());
    }

    // --- prefill -------------------------------------------------------------------------------------------

    @RequiresPrivilege(Privileges.TRIP_CMR_MANAGE)
    @Transactional
    public CmrDto.Cmr fromOrder(Long orderId) {
        TransportOrder o = order(orderId);
        Trip trip = o.getTripId() == null ? null : trips.findById(o.getTripId()).orElse(null);
        CmrDocument c = new CmrDocument();
        c.setFirmaId(o.getFirmaId());
        save(c, prefill(o, trip));
        audit.record(AuditEvent.of(MODULE, "cmr_created", "Added CMR " + c.getNumber() + " from order "
                + o.getNumber()).entity("cmr", c.getId()).detail("orderId", o.getId()));
        return get(c.getId());
    }

    /** One draft per order of the trip, or one from its first loading and last unloading stop. */
    @RequiresPrivilege(Privileges.TRIP_CMR_MANAGE)
    @Transactional
    public List<CmrDto.Cmr> fromTrip(Long tripId) {
        Long firmaId = InputFields.requireFirmaId();
        Trip trip = trips.findById(tripId).filter(t -> firmaId.equals(t.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
        List<CmrDto.Input> inputs = new ArrayList<>();
        for (TransportOrder o : orders.findByTripIdOrderByNumberAsc(trip.getId())) {
            if (o.getStatus() != TransportOrder.Status.CANCELLED) {
                inputs.add(prefill(o, trip));
            }
        }
        if (inputs.isEmpty()) {
            inputs.add(prefill(trip));
        }
        List<CmrDto.Cmr> result = new ArrayList<>();
        for (CmrDto.Input in : inputs) {
            CmrDocument c = new CmrDocument();
            c.setFirmaId(firmaId);
            save(c, in);
            audit.record(AuditEvent.of(MODULE, "cmr_created", "Added CMR " + c.getNumber() + " from trip "
                    + trip.getNumber()).entity("cmr", c.getId()).detail("tripId", trip.getId()));
            result.add(get(c.getId()));
        }
        return result;
    }

    private CmrDto.Input prefill(TransportOrder o, Trip trip) {
        Contractor customer = contractors.findById(o.getContractorId()).orElse(null);
        CmrDto.Party sender = customer == null ? null
                : new CmrDto.Party(customer.getName(), customer.getAddress(), customer.getCountry(),
                customer.getVatId());
        CmrDto.Party consignee = new CmrDto.Party(null, o.getUnloadingPlace(), o.getUnloadingCountry(), null);
        List<CmrDto.Line> lines = o.getCargo() == null && o.getWeightKg() == null ? List.of()
                : List.of(new CmrDto.Line(null, null, null, o.getCargo(), null,
                o.getWeightKg() == null ? null : BigDecimal.valueOf(o.getWeightKg()), null));
        LocalDate day = o.getLoadingDate();
        return new CmrDto.Input(null, o.getId(), trip == null ? null : trip.getId(), sender,
                customer == null ? null : customer.getId(), consignee, null, ownCarrier(), null,
                o.getUnloadingPlace(), o.getUnloadingCountry(), o.getLoadingPlace(), o.getLoadingCountry(), day, null,
                lines, null, null, null, null, null, null, city(o.getLoadingPlace()), day,
                plates(trip), null, null);
    }

    private CmrDto.Input prefill(Trip trip) {
        List<TripStop> list = stops.findByTripIdOrderBySeqAsc(trip.getId());
        TripStop loading = list.stream().filter(s -> s.getKind() == TripStop.Kind.LOADING).findFirst().orElse(null);
        TripStop unloading = list.stream().filter(s -> s.getKind() == TripStop.Kind.UNLOADING)
                .reduce((a, b) -> b).orElse(null);
        Firma firma = firmas.findById(trip.getFirmaId()).orElseThrow();
        LocalDate day = loading == null || loading.getPlannedAt() == null ? null
                : LocalDate.ofInstant(loading.getPlannedAt(), zone(firma));
        CmrDto.Party sender = loading == null ? null
                : new CmrDto.Party(loading.getName(), loading.getAddress(), loading.getCountry(), null);
        CmrDto.Party consignee = unloading == null ? null
                : new CmrDto.Party(unloading.getName(), unloading.getAddress(), unloading.getCountry(), null);
        return new CmrDto.Input(null, null, trip.getId(), sender, null, consignee, null, ownCarrier(), null,
                unloading == null ? null : unloading.getAddress(), unloading == null ? null : unloading.getCountry(),
                loading == null ? null : loading.getAddress(), loading == null ? null : loading.getCountry(), day,
                null, List.of(), null, null, null, null, null, null,
                loading == null ? null : city(loading.getAddress()), day, plates(trip), null, null);
    }

    private CmrDto.Party ownCarrier() {
        Firma f = firmas.findById(InputFields.requireFirmaId()).orElseThrow();
        return new CmrDto.Party(f.getName(), f.getAddress(), f.getCountry(), f.getVatId());
    }

    /** "WX 1234A / WX 5678B": the trip's vehicle and trailer. */
    private String plates(Trip trip) {
        if (trip == null) {
            return null;
        }
        List<String> plates = new ArrayList<>();
        for (Long id : new Long[] {trip.getVehicleId(), trip.getTrailerId()}) {
            if (id != null) {
                vehicles.findById(id).map(Vehicle::getPlate).ifPresent(plates::add);
            }
        }
        return plates.isEmpty() ? null : String.join(" / ", plates);
    }

    /** The town of an address "Poznań, ul. Magazynowa 1" -> "Poznań" (box 21). */
    private static String city(String place) {
        if (place == null || place.isBlank()) {
            return null;
        }
        String first = place.split(",")[0].strip();
        return first.length() > 100 ? first.substring(0, 100) : first;
    }

    // --- the full replacement ------------------------------------------------------------------------------

    private void save(CmrDocument c, CmrDto.Input in) {
        Long firmaId = c.getFirmaId();
        Map<String, String> errors = new LinkedHashMap<>();
        String number = Numbers.typed(in.number());
        if (number != null && !Numbers.valid(number)) {
            errors.put("number", ErrorCodes.FIELD_INVALID_FORMAT);
        } else if (number != null && cmrs.findByNumber(number).filter(x -> !x.getId().equals(c.getId())).isPresent()) {
            errors.put("number", ErrorCodes.CMR_NUMBER_TAKEN);
        }
        if (in.orderId() != null && orders.findById(in.orderId()).filter(o -> firmaId.equals(o.getFirmaId()))
                .isEmpty()) {
            errors.put("orderId", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (in.tripId() != null && trips.findById(in.tripId()).filter(t -> firmaId.equals(t.getFirmaId())).isEmpty()) {
            errors.put("tripId", ErrorCodes.FIELD_INVALID_VALUE);
        }
        contractor("senderContractorId", in.senderContractorId(), firmaId, errors);
        contractor("consigneeContractorId", in.consigneeContractorId(), firmaId, errors);
        CmrDto.Party sender = party("sender", in.sender(), errors);
        CmrDto.Party consignee = party("consignee", in.consignee(), errors);
        CmrDto.Party carrier = party("carrier", in.carrier(), errors);
        List<CmrDto.Party> successive = new ArrayList<>();
        if (in.successiveCarriers() != null) {
            for (int i = 0; i < in.successiveCarriers().size(); i++) {
                CmrDto.Party p = party("successiveCarriers[" + i + "]", in.successiveCarriers().get(i), errors);
                if (p != null) {
                    successive.add(p);
                }
            }
        }
        String deliveryCountry = InputFields.country("deliveryCountry", in.deliveryCountry(), errors);
        String loadingCountry = InputFields.country("loadingCountry", in.loadingCountry(), errors);
        List<CmrDto.Line> lines = new ArrayList<>();
        if (in.lines() != null) {
            for (int i = 0; i < in.lines().size(); i++) {
                CmrDto.Line l = in.lines().get(i);
                if (l == null) {
                    continue;
                }
                if (l.packages() != null && (l.packages() < 0 || l.packages() > 1_000_000)) {
                    errors.put("lines[" + i + "].packages", ErrorCodes.FIELD_INVALID_VALUE);
                }
                if (l.grossWeightKg() != null && l.grossWeightKg().signum() < 0) {
                    errors.put("lines[" + i + "].grossWeightKg", ErrorCodes.FIELD_INVALID_VALUE);
                }
                if (l.volumeM3() != null && l.volumeM3().signum() < 0) {
                    errors.put("lines[" + i + "].volumeM3", ErrorCodes.FIELD_INVALID_VALUE);
                }
                CmrDto.Line clean = new CmrDto.Line(InputFields.blankToNull(l.marks()), l.packages(),
                        InputFields.blankToNull(l.packing()), InputFields.blankToNull(l.nature()),
                        InputFields.blankToNull(l.statNumber()),
                        l.grossWeightKg() == null ? null : l.grossWeightKg().setScale(1, RoundingMode.HALF_UP),
                        l.volumeM3() == null ? null : l.volumeM3().setScale(3, RoundingMode.HALF_UP));
                if (!empty(clean)) {
                    lines.add(clean);
                }
            }
        }
        CmrDocument.PaymentTerms terms = InputFields.parseEnum(CmrDocument.PaymentTerms.class, "paymentTerms",
                in.paymentTerms(), false, errors);
        BigDecimal codAmount = null;
        String codCurrency = null;
        if (in.cashOnDelivery() != null && in.cashOnDelivery().amount() != null) {
            codAmount = in.cashOnDelivery().amount().setScale(2, RoundingMode.HALF_UP);
            codCurrency = InputFields.blankToNull(in.cashOnDelivery().currency());
            codCurrency = codCurrency == null ? null : codCurrency.toUpperCase(Locale.ROOT);
            if (codCurrency == null) {
                errors.put("cashOnDelivery.currency", ErrorCodes.FIELD_REQUIRED);
            } else if (!CURRENCY.matcher(codCurrency).matches()) {
                errors.put("cashOnDelivery.currency", ErrorCodes.FIELD_INVALID_FORMAT);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        if (number == null) {
            number = c.getNumber() != null ? c.getNumber() : nextNumber(in.loadingDate());
        }
        c.setNumber(number);
        c.setOrderId(in.orderId());
        c.setTripId(in.tripId());
        c.setSender(write(sender));
        c.setSenderContractorId(in.senderContractorId());
        c.setConsignee(write(consignee));
        c.setConsigneeContractorId(in.consigneeContractorId());
        c.setCarrier(write(carrier));
        c.setSuccessiveCarriers(successive.isEmpty() ? null : json.writeValueAsString(successive));
        c.setDeliveryPlace(InputFields.blankToNull(in.deliveryPlace()));
        c.setDeliveryCountry(deliveryCountry);
        c.setLoadingPlace(InputFields.blankToNull(in.loadingPlace()));
        c.setLoadingCountry(loadingCountry);
        c.setLoadingDate(in.loadingDate());
        c.setDocumentsAttached(InputFields.blankToNull(in.documentsAttached()));
        c.setGoods(lines.isEmpty() ? null : json.writeValueAsString(lines));
        c.setSenderInstructions(InputFields.blankToNull(in.senderInstructions()));
        c.setPaymentTerms(terms);
        c.setPaymentInstructions(InputFields.blankToNull(in.paymentInstructions()));
        c.setCodAmount(codAmount);
        c.setCodCurrency(codCurrency);
        c.setCarrierReservations(InputFields.blankToNull(in.carrierReservations()));
        c.setSpecialAgreements(InputFields.blankToNull(in.specialAgreements()));
        c.setEstablishedPlace(InputFields.blankToNull(in.establishedPlace()));
        c.setEstablishedDate(in.establishedDate());
        c.setVehiclePlates(InputFields.blankToNull(in.vehiclePlates()));
        c.setSignatures(in.signatures() == null ? null : json.writeValueAsString(in.signatures()));
        c.setNotes(InputFields.blankToNull(in.notes()));
        try {
            cmrs.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.validation(Map.of("number", ErrorCodes.CMR_NUMBER_TAKEN));
        }
    }

    /** CMR/2026/0001: the next of the year of loading (today when unknown). */
    private String nextNumber(LocalDate loadingDate) {
        int year = loadingDate != null ? loadingDate.getYear()
                : LocalDate.now(zone(firmas.findById(InputFields.requireFirmaId()).orElseThrow())).getYear();
        String prefix = NUMBER_PREFIX + "/" + year + "/";
        return Numbers.next(prefix, cmrs.numbersLike(prefix + "%"));
    }

    private CmrDto.Party party(String field, CmrDto.Party p, Map<String, String> errors) {
        if (p == null) {
            return null;
        }
        String country = InputFields.country(field + ".country", p.country(), errors);
        CmrDto.Party clean = new CmrDto.Party(InputFields.blankToNull(p.name()), InputFields.blankToNull(p.address()),
                country, InputFields.blankToNull(p.vatId()));
        return clean.name() == null && clean.address() == null && clean.country() == null && clean.vatId() == null
                ? null : clean;
    }

    private void contractor(String field, Long id, Long firmaId, Map<String, String> errors) {
        if (id != null && contractors.findByIdAndDeletedFalse(id).filter(c -> firmaId.equals(c.getFirmaId()))
                .isEmpty()) {
            errors.put(field, ErrorCodes.FIELD_INVALID_VALUE);
        }
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private CmrDocument load(Long id) {
        Long firmaId = InputFields.requireFirmaId();
        return cmrs.findById(id).filter(c -> firmaId.equals(c.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private CmrDocument draft(Long id) {
        CmrDocument c = load(id);
        if (c.getStatus() != CmrDocument.Status.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        return c;
    }

    private TransportOrder order(Long id) {
        Long firmaId = InputFields.requireFirmaId();
        return orders.findById(id).filter(o -> firmaId.equals(o.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private List<CmrDto.Cmr> toDtos(List<CmrDocument> list) {
        List<CmrDto.Cmr> result = new ArrayList<>();
        for (CmrDocument c : list) {
            TransportOrder o = c.getOrderId() == null ? null : orders.findById(c.getOrderId()).orElse(null);
            Trip t = c.getTripId() == null ? null : trips.findById(c.getTripId()).orElse(null);
            result.add(new CmrDto.Cmr(c.getId(), c.getNumber(), c.getStatus(),
                    o == null ? null : new CmrDto.NumberRef(o.getId(), o.getNumber()),
                    t == null ? null : new CmrDto.NumberRef(t.getId(), t.getNumber()),
                    read(c.getSender()), c.getSenderContractorId(), read(c.getConsignee()),
                    c.getConsigneeContractorId(), read(c.getCarrier()),
                    c.getSuccessiveCarriers() == null ? List.of() : json.readValue(c.getSuccessiveCarriers(), PARTIES),
                    c.getDeliveryPlace(), c.getDeliveryCountry(), c.getLoadingPlace(), c.getLoadingCountry(),
                    c.getLoadingDate(), c.getDocumentsAttached(),
                    c.getGoods() == null ? List.of() : json.readValue(c.getGoods(), LINES),
                    c.getSenderInstructions(), c.getPaymentTerms(), c.getPaymentInstructions(),
                    c.getCodAmount() == null ? null : new CmrDto.CashOnDelivery(c.getCodAmount(), c.getCodCurrency()),
                    c.getCarrierReservations(), c.getSpecialAgreements(), c.getEstablishedPlace(),
                    c.getEstablishedDate(), c.getVehiclePlates(),
                    c.getSignatures() == null ? null : json.readValue(c.getSignatures(), CmrDto.Signatures.class),
                    c.getNotes(), c.getFileId() != null, c.getIssuedAt(), c.getCreatedAt(), c.getUpdatedAt()));
        }
        return result;
    }

    private String write(CmrDto.Party p) {
        return p == null ? null : json.writeValueAsString(p);
    }

    private CmrDto.Party read(String value) {
        return value == null ? null : json.readValue(value, CmrDto.Party.class);
    }

    private static boolean empty(CmrDto.Line l) {
        return l.marks() == null && l.packages() == null && l.packing() == null && l.nature() == null
                && l.statNumber() == null && l.grossWeightKg() == null && l.volumeM3() == null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static ZoneId zone(Firma firma) {
        try {
            return ZoneId.of(Objects.requireNonNull(firma.getTimezone()));
        } catch (DateTimeException | NullPointerException e) {
            return ZoneOffset.UTC;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

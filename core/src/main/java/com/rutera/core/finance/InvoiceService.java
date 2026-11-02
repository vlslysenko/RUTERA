package com.rutera.core.finance;

import com.rutera.common.domain.Contractor;
import com.rutera.common.domain.Invoice;
import com.rutera.common.domain.InvoiceLine;
import com.rutera.common.domain.InvoicePayment;
import com.rutera.common.domain.ReminderSent;
import com.rutera.common.domain.TransportOrder;
import com.rutera.common.domain.Trip;
import com.rutera.common.repository.ContractorRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.repository.InvoiceLineRepository;
import com.rutera.common.repository.InvoicePaymentRepository;
import com.rutera.common.repository.InvoiceRepository;
import com.rutera.common.repository.ReminderSentRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Invoices, their payments and the debts ({@code /app/invoices}, {@code /app/debts}). The status of an invoice follows
 * from the cancelled flag, the payments and the due date on today's date in the firma's time zone. Linked orders
 * ({@code transport_order.invoice_id}) are INVOICED; unlinked again they follow their trip.
 */
@Service
public class InvoiceService {

    public static final String MODULE = "finance";
    static final int DEFAULT_PAYMENT_DAYS = 14;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final InvoicePaymentRepository payments;
    private final ReminderSentRepository reminders;
    private final ContractorRepository contractors;
    private final TransportOrderRepository orders;
    private final TripRepository trips;
    private final FirmaRepository firmas;
    private final AuditService audit;

    public InvoiceService(InvoiceRepository invoices, InvoiceLineRepository lines, InvoicePaymentRepository payments,
                          ReminderSentRepository reminders, ContractorRepository contractors,
                          TransportOrderRepository orders, TripRepository trips, FirmaRepository firmas,
                          AuditService audit) {
        this.invoices = invoices;
        this.lines = lines;
        this.payments = payments;
        this.reminders = reminders;
        this.contractors = contractors;
        this.orders = orders;
        this.trips = trips;
        this.firmas = firmas;
        this.audit = audit;
    }

    // --- invoices ------------------------------------------------------------------------------------------

    @RequiresPrivilege(Privileges.FINANCE_INVOICE_VIEW)
    @Transactional(readOnly = true)
    public FinanceDto.Page list(String direction, String status, Long contractorId, String q, LocalDate from,
                                LocalDate to, boolean overdue, int page, int size) {
        Long firmaId = InputFields.requireFirmaId();
        Map<String, String> errors = new LinkedHashMap<>();
        Invoice.Direction d = InputFields.parseEnum(Invoice.Direction.class, "direction", direction, false, errors);
        Invoice.Status s = InputFields.parseEnum(Invoice.Status.class, "status", status, false, errors);
        if (page < 0) {
            errors.put("page", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (size < 1 || size > 200) {
            errors.put("size", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        LocalDate today = today(firmaId);
        String like = q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
        List<Invoice> all = invoices.search(d, contractorId, from, to, like).stream()
                .filter(i -> s == null || i.statusOn(today) == s)
                .filter(i -> !overdue || i.statusOn(today) == Invoice.Status.OVERDUE)
                .toList();
        Map<String, BigDecimal[]> sums = new TreeMap<>();
        for (Invoice i : all) {
            if (i.isCancelled()) {
                continue;
            }
            BigDecimal[] t = sums.computeIfAbsent(i.getCurrency(), c -> new BigDecimal[] {ZERO, ZERO});
            t[0] = t[0].add(i.getGross());
            t[1] = t[1].add(i.openAmount());
        }
        List<FinanceDto.Total> totals = sums.entrySet().stream()
                .map(e -> new FinanceDto.Total(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        int fromIndex = Math.min(all.size(), page * size);
        List<Invoice> slice = all.subList(fromIndex, Math.min(all.size(), fromIndex + size));
        return new FinanceDto.Page(toDtos(slice, today), all.size(), totals);
    }

    @RequiresPrivilege(Privileges.FINANCE_INVOICE_VIEW)
    @Transactional(readOnly = true)
    public FinanceDto.Detail get(Long id) {
        Invoice i = load(id);
        return detail(i);
    }

    @RequiresPrivilege(Privileges.FINANCE_INVOICE_MANAGE)
    @Transactional
    public FinanceDto.Detail create(FinanceDto.InvoiceInput input) {
        Invoice i = new Invoice();
        i.setFirmaId(InputFields.requireFirmaId());
        save(i, input);
        audit.record(AuditEvent.of(MODULE, "invoice_created", "Recorded invoice " + i.getNumber())
                .entity("invoice", i.getId()).detail("direction", i.getDirection().name()));
        return detail(i);
    }

    @RequiresPrivilege(Privileges.FINANCE_INVOICE_MANAGE)
    @Transactional
    public FinanceDto.Detail update(Long id, FinanceDto.InvoiceInput input) {
        Invoice i = load(id);
        save(i, input);
        audit.record(AuditEvent.of(MODULE, "invoice_updated", "Updated invoice " + i.getNumber())
                .entity("invoice", i.getId()).detail("cancelled", i.isCancelled()));
        return detail(i);
    }

    /** Only an invoice without payments can be deleted; its orders follow their trips again. */
    @RequiresPrivilege(Privileges.FINANCE_INVOICE_MANAGE)
    @Transactional
    public void delete(Long id) {
        Invoice i = load(id);
        if (!payments.findByInvoiceIdOrderByPaidAtAscIdAsc(i.getId()).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        for (TransportOrder o : orders.findByInvoiceIdOrderByNumberAsc(i.getId())) {
            unlink(o);
        }
        reminders.deleteAll(reminders.findByInvoiceIdOrderByIdDesc(i.getId()));
        lines.deleteAll(lines.findByInvoiceIdOrderBySeqAsc(i.getId()));
        orders.flush();
        lines.flush();
        reminders.flush();
        invoices.delete(i);
        audit.record(AuditEvent.of(MODULE, "invoice_deleted", "Deleted invoice " + i.getNumber())
                .entity("invoice", i.getId()));
    }

    // --- payments ------------------------------------------------------------------------------------------

    @RequiresPrivilege(Privileges.FINANCE_PAYMENT_MANAGE)
    @Transactional
    public FinanceDto.Payment addPayment(Long invoiceId, FinanceDto.PaymentInput input) {
        Invoice i = load(invoiceId);
        if (i.isCancelled()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        Map<String, String> errors = new LinkedHashMap<>();
        if (input.paidAt() == null) {
            errors.put("paidAt", ErrorCodes.FIELD_REQUIRED);
        }
        if (input.amount() == null) {
            errors.put("amount", ErrorCodes.FIELD_REQUIRED);
        } else if (i.getPaidAmount().add(input.amount()).compareTo(i.getGross()) > 0) {
            errors.put("amount", ErrorCodes.PAYMENT_TOO_MUCH);
        }
        InvoicePayment.Method method = InputFields.parseEnum(InvoicePayment.Method.class, "method", input.method(),
                false, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        InvoicePayment p = new InvoicePayment();
        p.setFirmaId(i.getFirmaId());
        p.setInvoiceId(i.getId());
        p.setPaidAt(input.paidAt());
        p.setAmount(money(input.amount()));
        p.setCurrency(i.getCurrency());
        p.setMethod(method == null ? InvoicePayment.Method.TRANSFER : method);
        p.setReference(InputFields.blankToNull(input.reference()));
        p.setNote(InputFields.blankToNull(input.note()));
        p = payments.save(p);
        refreshPaid(i);
        audit.record(AuditEvent.of(MODULE, "payment_added", "Payment of " + p.getAmount() + " " + p.getCurrency()
                + " on invoice " + i.getNumber()).entity("invoice_payment", p.getId()).detail("invoiceId", i.getId()));
        return toPayment(p);
    }

    @RequiresPrivilege(Privileges.FINANCE_PAYMENT_MANAGE)
    @Transactional
    public void deletePayment(Long invoiceId, Long paymentId) {
        Invoice i = load(invoiceId);
        InvoicePayment p = payments.findById(paymentId)
                .filter(x -> x.getInvoiceId().equals(i.getId()) && i.getFirmaId().equals(x.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
        payments.delete(p);
        payments.flush();
        refreshPaid(i);
        audit.record(AuditEvent.of(MODULE, "payment_deleted", "Deleted a payment of invoice " + i.getNumber())
                .entity("invoice_payment", p.getId()).detail("invoiceId", i.getId()));
    }

    private void refreshPaid(Invoice i) {
        BigDecimal paid = payments.findByInvoiceIdOrderByPaidAtAscIdAsc(i.getId()).stream()
                .map(InvoicePayment::getAmount).reduce(ZERO, BigDecimal::add);
        i.setPaidAmount(paid);
        invoices.save(i);
    }

    // --- debts ---------------------------------------------------------------------------------------------

    /** Receivables per contractor and currency on {@code asOf} (today by default), most overdue first. */
    @RequiresPrivilege(Privileges.FINANCE_INVOICE_VIEW)
    @Transactional(readOnly = true)
    public List<FinanceDto.DebtRow> debts(LocalDate asOf) {
        Long firmaId = InputFields.requireFirmaId();
        LocalDate day = asOf == null ? today(firmaId) : asOf;
        Map<String, List<Invoice>> groups = new LinkedHashMap<>();
        for (Invoice i : invoices.search(Invoice.Direction.SALES, null, null, null, null)) {
            if (!i.isCancelled() && i.openAmount().signum() > 0) {
                groups.computeIfAbsent(i.getContractorId() + "|" + i.getCurrency(), k -> new ArrayList<>()).add(i);
            }
        }
        Map<Long, Contractor> contractorOf = byId(contractors.findAllById(groups.values().stream()
                .flatMap(List::stream).map(Invoice::getContractorId).collect(Collectors.toSet())), Contractor::getId);
        List<FinanceDto.DebtRow> rows = new ArrayList<>();
        for (List<Invoice> list : groups.values()) {
            BigDecimal open = ZERO;
            BigDecimal overdue = ZERO;
            BigDecimal[] buckets = {ZERO, ZERO, ZERO, ZERO, ZERO};
            LocalDate oldest = null;
            int maxDays = 0;
            for (Invoice i : list) {
                BigDecimal amount = i.openAmount();
                open = open.add(amount);
                long days = ChronoUnit.DAYS.between(i.getDueDate(), day);
                int bucket = days <= 0 ? 0 : days <= 30 ? 1 : days <= 60 ? 2 : days <= 90 ? 3 : 4;
                buckets[bucket] = buckets[bucket].add(amount);
                if (days > 0) {
                    overdue = overdue.add(amount);
                    maxDays = (int) Math.max(maxDays, days);
                }
                oldest = oldest == null || i.getDueDate().isBefore(oldest) ? i.getDueDate() : oldest;
            }
            Invoice first = list.getFirst();
            Contractor c = contractorOf.get(first.getContractorId());
            rows.add(new FinanceDto.DebtRow(FinanceDto.ref(first.getContractorId(), c == null ? null : c.getName()),
                    first.getCurrency(), open, overdue, list.size(), oldest, maxDays,
                    new FinanceDto.Buckets(buckets[0], buckets[1], buckets[2], buckets[3], buckets[4])));
        }
        rows.sort(Comparator.comparing(FinanceDto.DebtRow::overdue).reversed()
                .thenComparing(FinanceDto.DebtRow::open, Comparator.reverseOrder()));
        return rows;
    }

    // --- the full replacement ------------------------------------------------------------------------------

    private record Totals(BigDecimal net, BigDecimal vat, BigDecimal gross) {
    }

    private void save(Invoice i, FinanceDto.InvoiceInput in) {
        Map<String, String> errors = new LinkedHashMap<>();
        Long firmaId = i.getFirmaId();
        Invoice.Direction direction = InputFields.parseEnum(Invoice.Direction.class, "direction", in.direction(),
                false, errors);
        if (direction == null) {
            direction = Invoice.Direction.SALES;
        }
        String number = InputFields.blankToNull(in.number());
        if (number == null) {
            if (direction != Invoice.Direction.SALES) {
                errors.put("number", ErrorCodes.FIELD_REQUIRED);
            }
        } else {
            Invoice.Direction dir = direction;
            if (invoices.findByDirectionAndNumber(dir, number).filter(x -> !x.getId().equals(i.getId())).isPresent()) {
                errors.put("number", ErrorCodes.INVOICE_NUMBER_TAKEN);
            }
        }
        Contractor contractor = in.contractorId() == null ? null : contractors.findByIdAndDeletedFalse(in.contractorId())
                .filter(c -> firmaId.equals(c.getFirmaId())).orElse(null);
        if (in.contractorId() == null) {
            errors.put("contractorId", ErrorCodes.FIELD_REQUIRED);
        } else if (contractor == null) {
            errors.put("contractorId", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (in.issueDate() == null) {
            errors.put("issueDate", ErrorCodes.FIELD_REQUIRED);
        }
        LocalDate due = in.dueDate();
        if (due == null && in.issueDate() != null) {
            int days = contractor != null && contractor.getPaymentDays() != null ? contractor.getPaymentDays()
                    : DEFAULT_PAYMENT_DAYS;
            due = in.issueDate().plusDays(days);
        }
        InputFields.dateOrder("dueDate", in.issueDate(), due, errors);
        String currency = InputFields.blankToNull(in.currency());
        if (currency == null) {
            errors.put("currency", ErrorCodes.FIELD_REQUIRED);
        } else {
            currency = currency.toUpperCase(Locale.ROOT);
            if (!CURRENCY.matcher(currency).matches()) {
                errors.put("currency", ErrorCodes.FIELD_INVALID_FORMAT);
            }
        }
        List<InvoiceLine> newLines = lines(in.lines(), errors);
        Totals totals = totals(in, newLines, errors);
        List<TransportOrder> billed = orders(i, direction, contractor, in.orderIds(), errors);
        Trip trip = null;
        if (in.tripId() != null) {
            trip = trips.findById(in.tripId()).filter(t -> firmaId.equals(t.getFirmaId())).orElse(null);
            if (trip == null) {
                errors.put("tripId", ErrorCodes.FIELD_INVALID_VALUE);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }

        if (number == null) {
            number = i.getNumber() != null && i.getDirection() == Invoice.Direction.SALES ? i.getNumber()
                    : nextSalesNumber(in.issueDate());
        }
        i.setDirection(direction);
        i.setNumber(number);
        i.setContractorId(contractor.getId());
        i.setIssueDate(in.issueDate());
        i.setDueDate(due);
        i.setCurrency(currency);
        i.setNet(totals.net());
        i.setVat(totals.vat());
        i.setGross(totals.gross());
        i.setTripId(trip == null ? null : trip.getId());
        i.setRemindersOn(in.remindersOn() != null ? in.remindersOn() : direction == Invoice.Direction.SALES);
        i.setCancelled(Objects.equals(in.cancelled(), Boolean.TRUE));
        i.setNotes(InputFields.blankToNull(in.notes()));
        try {
            invoices.saveAndFlush(i);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.validation(Map.of("number", ErrorCodes.INVOICE_NUMBER_TAKEN));
        }
        List<InvoiceLine> old = lines.findByInvoiceIdOrderBySeqAsc(i.getId());
        if (!old.isEmpty()) {
            lines.deleteAll(old);
            lines.flush();
        }
        for (InvoiceLine l : newLines) {
            l.setFirmaId(firmaId);
            l.setInvoiceId(i.getId());
        }
        lines.saveAll(newLines);
        Set<Long> keep = i.isCancelled() ? Set.of()
                : billed.stream().map(TransportOrder::getId).collect(Collectors.toSet());
        for (TransportOrder o : orders.findByInvoiceIdOrderByNumberAsc(i.getId())) {
            if (!keep.contains(o.getId())) {
                unlink(o);
            }
        }
        if (!i.isCancelled()) {
            for (TransportOrder o : billed) {
                o.setInvoiceId(i.getId());
                o.setStatus(TransportOrder.Status.INVOICED);
                orders.save(o);
            }
        }
        orders.flush();
    }

    private List<InvoiceLine> lines(List<FinanceDto.LineInput> input, Map<String, String> errors) {
        List<InvoiceLine> result = new ArrayList<>();
        if (input == null) {
            return result;
        }
        for (int n = 0; n < input.size(); n++) {
            FinanceDto.LineInput in = input.get(n);
            String at = "lines[" + n + "].";
            if (in == null) {
                errors.put("lines[" + n + "]", ErrorCodes.FIELD_REQUIRED);
                continue;
            }
            String description = InputFields.blankToNull(in.description());
            if (description == null) {
                errors.put(at + "description", ErrorCodes.FIELD_REQUIRED);
            }
            if (in.qty() == null) {
                errors.put(at + "qty", ErrorCodes.FIELD_REQUIRED);
            }
            if (in.unitPrice() == null) {
                errors.put(at + "unitPrice", ErrorCodes.FIELD_REQUIRED);
            }
            InvoiceLine.VatCode code = InputFields.parseEnum(InvoiceLine.VatCode.class, at + "vatCode", in.vatCode(),
                    false, errors);
            if (code == null) {
                code = InvoiceLine.VatCode.RATE;
            }
            if (code == InvoiceLine.VatCode.RATE && in.vatRate() == null) {
                errors.put(at + "vatRate", ErrorCodes.FIELD_REQUIRED);
            }
            if (description == null || in.qty() == null || in.unitPrice() == null
                    || (code == InvoiceLine.VatCode.RATE && in.vatRate() == null)) {
                continue;
            }
            InvoiceLine l = new InvoiceLine();
            l.setSeq(n + 1);
            l.setDescription(description);
            l.setQty(in.qty().setScale(3, RoundingMode.HALF_UP));
            l.setUnit(InputFields.blankToNull(in.unit()));
            l.setUnitPrice(in.unitPrice().setScale(4, RoundingMode.HALF_UP));
            l.setVatCode(code);
            l.setVatRate(code == InvoiceLine.VatCode.RATE ? in.vatRate().setScale(2, RoundingMode.HALF_UP) : null);
            BigDecimal net = money(in.qty().multiply(in.unitPrice()));
            BigDecimal vat = code == InvoiceLine.VatCode.RATE
                    ? money(net.multiply(in.vatRate()).divide(HUNDRED, 4, RoundingMode.HALF_UP)) : ZERO;
            l.setNet(net);
            l.setVat(vat);
            l.setGross(net.add(vat));
            result.add(l);
        }
        return result;
    }

    /** From the lines when there are any, otherwise from the header (net and gross required). */
    private static Totals totals(FinanceDto.InvoiceInput in, List<InvoiceLine> lines, Map<String, String> errors) {
        if (in.lines() != null && !in.lines().isEmpty()) {
            BigDecimal net = lines.stream().map(InvoiceLine::getNet).reduce(ZERO, BigDecimal::add);
            BigDecimal vat = lines.stream().map(InvoiceLine::getVat).reduce(ZERO, BigDecimal::add);
            return new Totals(net, vat, net.add(vat));
        }
        if (in.net() == null) {
            errors.put("net", ErrorCodes.FIELD_REQUIRED);
        }
        if (in.gross() == null) {
            errors.put("gross", ErrorCodes.FIELD_REQUIRED);
        }
        if (in.net() == null || in.gross() == null) {
            return new Totals(ZERO, ZERO, ZERO);
        }
        BigDecimal net = money(in.net());
        BigDecimal gross = money(in.gross());
        BigDecimal vat = in.vat() == null ? gross.subtract(net) : money(in.vat());
        if (net.add(vat).compareTo(gross) != 0) {
            errors.put("gross", ErrorCodes.FIELD_INVALID_VALUE);
        }
        return new Totals(net, vat, gross);
    }

    /** Orders a sales invoice bills: of the firma and the invoice's contractor, not cancelled, not on another invoice. */
    private List<TransportOrder> orders(Invoice i, Invoice.Direction direction, Contractor contractor, List<Long> ids,
                                        Map<String, String> errors) {
        List<TransportOrder> result = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        if (direction != Invoice.Direction.SALES) {
            errors.put("orderIds", ErrorCodes.FIELD_INVALID_VALUE);
            return result;
        }
        for (Long id : new LinkedHashSet<>(ids)) {
            TransportOrder o = id == null ? null : orders.findById(id)
                    .filter(x -> i.getFirmaId().equals(x.getFirmaId())).orElse(null);
            boolean ok = o != null && o.getStatus() != TransportOrder.Status.CANCELLED
                    && (o.getInvoiceId() == null || o.getInvoiceId().equals(i.getId()))
                    && (contractor == null || contractor.getId().equals(o.getContractorId()));
            if (!ok) {
                errors.put("orderIds", ErrorCodes.FIELD_INVALID_VALUE);
                continue;
            }
            result.add(o);
        }
        return result;
    }

    /** The next sales number of the issue month: FV/2026/11/001 (simple default series until firma settings). */
    private String nextSalesNumber(LocalDate issueDate) {
        String prefix = String.format(Locale.ROOT, "FV/%d/%02d/", issueDate.getYear(), issueDate.getMonthValue());
        int max = 0;
        for (String n : invoices.numbersStartingWith(Invoice.Direction.SALES, prefix)) {
            String tail = n.substring(prefix.length());
            if (!tail.isEmpty() && tail.length() < 9 && tail.chars().allMatch(Character::isDigit)) {
                max = Math.max(max, Integer.parseInt(tail));
            }
        }
        return prefix + String.format(Locale.ROOT, "%03d", max + 1);
    }

    /** An order taken off its invoice follows its trip again (NEW without a trip). */
    private void unlink(TransportOrder o) {
        o.setInvoiceId(null);
        Trip trip = o.getTripId() == null ? null : trips.findById(o.getTripId()).orElse(null);
        o.setStatus(trip == null ? TransportOrder.Status.NEW : switch (trip.getStatus()) {
            case PLANNED -> TransportOrder.Status.PLANNED;
            case RUNNING -> TransportOrder.Status.IN_PROGRESS;
            case DONE -> TransportOrder.Status.DONE;
            case CANCELLED -> TransportOrder.Status.NEW;
        });
        orders.save(o);
    }

    // --- helpers -------------------------------------------------------------------------------------------

    Invoice load(Long id) {
        Long firmaId = InputFields.requireFirmaId();
        return invoices.findById(id).filter(i -> firmaId.equals(i.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    LocalDate today(Long firmaId) {
        String zone = firmas.findById(firmaId).map(f -> f.getTimezone()).orElse("UTC");
        try {
            return LocalDate.now(ZoneId.of(zone));
        } catch (DateTimeException e) {
            return LocalDate.now(ZoneOffset.UTC);
        }
    }

    private FinanceDto.Detail detail(Invoice i) {
        LocalDate today = today(i.getFirmaId());
        FinanceDto.Invoice dto = toDtos(List.of(i), today).getFirst();
        List<FinanceDto.Line> lineDtos = lines.findByInvoiceIdOrderBySeqAsc(i.getId()).stream()
                .map(l -> new FinanceDto.Line(l.getId(), l.getSeq(), l.getDescription(), l.getQty(), l.getUnit(),
                        l.getUnitPrice(), l.getVatCode(), l.getVatRate(), l.getNet(), l.getVat(), l.getGross()))
                .toList();
        List<FinanceDto.Payment> paymentDtos = payments.findByInvoiceIdOrderByPaidAtAscIdAsc(i.getId()).stream()
                .map(InvoiceService::toPayment).toList();
        List<FinanceDto.Reminder> reminderDtos = reminders.findByInvoiceIdOrderByIdDesc(i.getId()).stream()
                .map(InvoiceService::toReminder).toList();
        return new FinanceDto.Detail(dto, lineDtos, paymentDtos, reminderDtos);
    }

    private List<FinanceDto.Invoice> toDtos(List<Invoice> list, LocalDate today) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(Invoice::getId).toList();
        Map<Long, Contractor> contractorOf = byId(contractors.findAllById(list.stream().map(Invoice::getContractorId)
                .collect(Collectors.toSet())), Contractor::getId);
        Map<Long, Trip> tripOf = byId(trips.findAllById(list.stream().map(Invoice::getTripId).filter(Objects::nonNull)
                .collect(Collectors.toSet())), Trip::getId);
        Map<Long, List<FinanceDto.NumberRef>> ordersOf = new HashMap<>();
        for (TransportOrder o : orders.findByInvoiceIdIn(ids)) {
            ordersOf.computeIfAbsent(o.getInvoiceId(), k -> new ArrayList<>())
                    .add(new FinanceDto.NumberRef(o.getId(), o.getNumber()));
        }
        Map<Long, Instant> lastReminder = new HashMap<>();
        for (ReminderSent r : reminders.findByInvoiceIdIn(ids)) {
            Instant at = r.getSentAt() != null ? r.getSentAt() : r.getCreatedAt();
            lastReminder.merge(r.getInvoiceId(), at, (a, b) -> a.isAfter(b) ? a : b);
        }
        return list.stream().map(i -> {
            Contractor c = contractorOf.get(i.getContractorId());
            Trip t = i.getTripId() == null ? null : tripOf.get(i.getTripId());
            Invoice.Status status = i.statusOn(today);
            int overdueDays = status == Invoice.Status.OVERDUE
                    ? (int) ChronoUnit.DAYS.between(i.getDueDate(), today) : 0;
            List<FinanceDto.NumberRef> billed = new ArrayList<>(ordersOf.getOrDefault(i.getId(), List.of()));
            billed.sort(Comparator.comparing(FinanceDto.NumberRef::number));
            return new FinanceDto.Invoice(i.getId(), i.getDirection(), i.getNumber(),
                    FinanceDto.ref(i.getContractorId(), c == null ? null : c.getName()), i.getIssueDate(),
                    i.getDueDate(), i.getCurrency(), i.getNet(), i.getVat(), i.getGross(), i.getPaidAmount(),
                    i.openAmount(), status, overdueDays, billed,
                    t == null ? null : new FinanceDto.NumberRef(t.getId(), t.getNumber()), i.isRemindersOn(),
                    lastReminder.get(i.getId()), i.getSource(), i.getNotes(), i.getCreatedAt(), i.getUpdatedAt());
        }).toList();
    }

    static FinanceDto.Payment toPayment(InvoicePayment p) {
        return new FinanceDto.Payment(p.getId(), p.getPaidAt(), p.getAmount(), p.getCurrency(), p.getMethod(),
                p.getReference(), p.getNote(), p.getCreatedAt());
    }

    static FinanceDto.Reminder toReminder(ReminderSent r) {
        return new FinanceDto.Reminder(r.getId(), r.getOffsetDays(), r.getSentAt(),
                Arrays.stream(r.getToEmails().split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList(),
                r.getSubject(), r.getStatus(), r.getError(), r.getCreatedAt());
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static <T> Map<Long, T> byId(Collection<T> items, Function<T, Long> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }
}

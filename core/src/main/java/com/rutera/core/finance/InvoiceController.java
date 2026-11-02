package com.rutera.core.finance;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Invoices of the signed-in user's firma, their payments and reminders.
 * <pre>
 * GET    /app/invoices?direction&status&contractorId&q&from&to&overdue&page&size
 *                                       -> {items: Invoice[], total, totals}       (finance.invoice/view)
 * GET    /app/invoices/{id}             -> InvoiceDetail | 404                     (finance.invoice/view)
 * POST   /app/invoices                  {input} -> 201 InvoiceDetail               (finance.invoice/manage)
 * PUT    /app/invoices/{id}             {input} -> InvoiceDetail                   (finance.invoice/manage)
 * DELETE /app/invoices/{id}             -> 204; 409 with payments                  (finance.invoice/manage)
 * POST   /app/invoices/{id}/payments    {paidAt, amount, ...} -> 201 Payment       (finance.payment/manage)
 * DELETE /app/invoices/{id}/payments/{paymentId} -> 204                            (finance.payment/manage)
 * GET    /app/invoices/{id}/reminders   -> Reminder[]                              (finance.invoice/view)
 * POST   /app/invoices/{id}/reminders   -> 201 Reminder (queued now)               (finance.reminder/manage)
 * </pre>
 */
@RestController
@RequestMapping({"/app/invoices", "/api/v1/invoices"})
public class InvoiceController {

    private final InvoiceService service;
    private final ReminderService reminders;

    public InvoiceController(InvoiceService service, ReminderService reminders) {
        this.service = service;
        this.reminders = reminders;
    }

    @GetMapping
    public FinanceDto.Page list(@RequestParam(required = false) String direction,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) Long contractorId,
                                @RequestParam(required = false) String q,
                                @RequestParam(required = false) LocalDate from,
                                @RequestParam(required = false) LocalDate to,
                                @RequestParam(defaultValue = "false") boolean overdue,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
        return service.list(direction, status, contractorId, q, from, to, overdue, page, size);
    }

    @GetMapping("/{id}")
    public FinanceDto.Detail get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FinanceDto.Detail create(@Valid @RequestBody FinanceDto.InvoiceInput input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    public FinanceDto.Detail update(@PathVariable Long id, @Valid @RequestBody FinanceDto.InvoiceInput input) {
        return service.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public FinanceDto.Payment addPayment(@PathVariable Long id, @Valid @RequestBody FinanceDto.PaymentInput input) {
        return service.addPayment(id, input);
    }

    @DeleteMapping("/{id}/payments/{paymentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePayment(@PathVariable Long id, @PathVariable Long paymentId) {
        service.deletePayment(id, paymentId);
    }

    @GetMapping("/{id}/reminders")
    public List<FinanceDto.Reminder> reminders(@PathVariable Long id) {
        return reminders.reminders(id);
    }

    @PostMapping("/{id}/reminders")
    @ResponseStatus(HttpStatus.CREATED)
    public FinanceDto.Reminder sendReminder(@PathVariable Long id) {
        return reminders.sendNow(id);
    }
}

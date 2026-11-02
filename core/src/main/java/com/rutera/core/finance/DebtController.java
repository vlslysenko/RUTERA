package com.rutera.core.finance;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Receivables and the payment reminder plan.
 * <pre>
 * GET /app/debts?asOf                -> DebtRow[] per contractor and currency  (finance.invoice/view)
 * GET /app/reminders/schedule        -> Schedule        (finance.invoice/view or finance.reminder/manage)
 * PUT /app/reminders/schedule        {sendHour, ccEmails, stopWhenPaid, steps} -> Schedule (finance.reminder/manage)
 * </pre>
 */
@RestController
public class DebtController {

    private final InvoiceService invoices;
    private final ReminderService reminders;

    public DebtController(InvoiceService invoices, ReminderService reminders) {
        this.invoices = invoices;
        this.reminders = reminders;
    }

    @GetMapping({"/app/debts", "/api/v1/debts"})
    public List<FinanceDto.DebtRow> debts(@RequestParam(required = false) LocalDate asOf) {
        return invoices.debts(asOf);
    }

    @GetMapping({"/app/reminders/schedule", "/api/v1/reminders/schedule"})
    public FinanceDto.Schedule schedule() {
        return reminders.schedule();
    }

    @PutMapping({"/app/reminders/schedule", "/api/v1/reminders/schedule"})
    public FinanceDto.Schedule updateSchedule(@Valid @RequestBody FinanceDto.ScheduleInput input) {
        return reminders.updateSchedule(input);
    }
}

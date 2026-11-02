package com.rutera.core.finance;

import com.rutera.common.context.RequestContext;
import com.rutera.common.domain.Contractor;
import com.rutera.common.domain.EmailOutbox;
import com.rutera.common.domain.Firma;
import com.rutera.common.domain.Invoice;
import com.rutera.common.domain.ReminderSchedule;
import com.rutera.common.domain.ReminderSent;
import com.rutera.common.i18n.LangService;
import com.rutera.common.i18n.SupportedLanguages;
import com.rutera.common.repository.ContractorRepository;
import com.rutera.common.repository.EmailOutboxRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.repository.InvoiceRepository;
import com.rutera.common.repository.ReminderScheduleRepository;
import com.rutera.common.repository.ReminderSentRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Payment reminders for sales invoices: the firma's plan ({@code /app/reminders/schedule}), reminders by hand and the
 * daily run that queues them by the plan's steps (days from the due date). Every reminder is an {@link EmailOutbox}
 * row plus a {@link ReminderSent} entry; {@link com.rutera.core.mail.OutboxSender} delivers the e-mails. Texts come
 * from the i18n bundles in the contractor's language ({@code finance.reminder.mail.*}), as plain text.
 */
@Service
public class ReminderService {

    public static final String MODULE = "finance";
    public static final String TEMPLATE = "invoice_reminder";
    static final List<FinanceDto.Step> DEFAULT_STEPS = List.of(new FinanceDto.Step(-3, false),
            new FinanceDto.Step(1, false), new FinanceDto.Step(7, false), new FinanceDto.Step(14, true),
            new FinanceDto.Step(30, true));

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final TypeReference<List<FinanceDto.Step>> STEPS = new TypeReference<>() {
    };

    private final ReminderScheduleRepository schedules;
    private final ReminderSentRepository sent;
    private final EmailOutboxRepository outbox;
    private final InvoiceRepository invoices;
    private final ContractorRepository contractors;
    private final FirmaRepository firmas;
    private final InvoiceService invoiceService;
    private final LangService lang;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ReminderService(ReminderScheduleRepository schedules, ReminderSentRepository sent,
                           EmailOutboxRepository outbox, InvoiceRepository invoices, ContractorRepository contractors,
                           FirmaRepository firmas, InvoiceService invoiceService, LangService lang, AuditService audit,
                           ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.schedules = schedules;
        this.sent = sent;
        this.outbox = outbox;
        this.invoices = invoices;
        this.contractors = contractors;
        this.firmas = firmas;
        this.invoiceService = invoiceService;
        this.lang = lang;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // --- plan ----------------------------------------------------------------------------------------------

    @RequiresPrivilege({Privileges.FINANCE_INVOICE_VIEW, Privileges.FINANCE_REMINDER_MANAGE})
    @Transactional
    public FinanceDto.Schedule schedule() {
        return toDto(scheduleOf(InputFields.requireFirmaId()));
    }

    @RequiresPrivilege(Privileges.FINANCE_REMINDER_MANAGE)
    @Transactional
    public FinanceDto.Schedule updateSchedule(FinanceDto.ScheduleInput input) {
        ReminderSchedule s = scheduleOf(InputFields.requireFirmaId());
        Map<String, String> errors = new LinkedHashMap<>();
        if (input.sendHour() != null && (input.sendHour() < 0 || input.sendHour() > 23)) {
            errors.put("sendHour", ErrorCodes.FIELD_INVALID_VALUE);
        }
        List<String> cc = new ArrayList<>();
        if (input.ccEmails() != null) {
            for (String raw : input.ccEmails()) {
                String e = InputFields.blankToNull(raw);
                if (e != null && !EMAIL.matcher(e).matches()) {
                    errors.put("ccEmails", ErrorCodes.FIELD_INVALID_FORMAT);
                } else if (e != null && !cc.contains(e)) {
                    cc.add(e);
                }
            }
        }
        List<FinanceDto.Step> steps = new ArrayList<>(input.steps() == null ? DEFAULT_STEPS : List.of());
        Set<Integer> offsets = new LinkedHashSet<>();
        for (FinanceDto.StepInput step : input.steps() == null ? List.<FinanceDto.StepInput>of() : input.steps()) {
            if (step == null || step.offsetDays() == null || step.offsetDays() < -60 || step.offsetDays() > 365
                    || !offsets.add(step.offsetDays())) {
                errors.put("steps", ErrorCodes.FIELD_INVALID_VALUE);
            } else {
                steps.add(new FinanceDto.Step(step.offsetDays(), Boolean.TRUE.equals(step.escalate())));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        if (input.sendHour() != null) {
            s.setSendHour(input.sendHour());
        }
        s.setCcEmails(cc.isEmpty() ? null : json.writeValueAsString(cc));
        if (input.stopWhenPaid() != null) {
            s.setStopWhenPaid(input.stopWhenPaid());
        }
        s.setSteps(json.writeValueAsString(steps.stream()
                .sorted((a, b) -> Integer.compare(a.offsetDays(), b.offsetDays())).toList()));
        schedules.save(s);
        audit.record(AuditEvent.of(MODULE, "reminder_schedule_updated", "Updated the payment reminder plan")
                .entity("reminder_schedule", s.getId()));
        return toDto(s);
    }

    // --- reminders -----------------------------------------------------------------------------------------

    @RequiresPrivilege(Privileges.FINANCE_INVOICE_VIEW)
    @Transactional(readOnly = true)
    public List<FinanceDto.Reminder> reminders(Long invoiceId) {
        Invoice i = invoiceService.load(invoiceId);
        return sent.findByInvoiceIdOrderByIdDesc(i.getId()).stream().map(InvoiceService::toReminder).toList();
    }

    /** A reminder by hand for an open sales invoice; 422 when the contractor has no invoice e-mail. */
    @RequiresPrivilege(Privileges.FINANCE_REMINDER_MANAGE)
    @Transactional
    public FinanceDto.Reminder sendNow(Long invoiceId) {
        Invoice i = invoiceService.load(invoiceId);
        if (i.getDirection() != Invoice.Direction.SALES || i.isCancelled() || i.openAmount().signum() == 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        ReminderSent r = queue(i, null, scheduleOf(i.getFirmaId()), false, invoiceService.today(i.getFirmaId()));
        if (r == null) {
            throw ApiException.validation(Map.of("contractor", ErrorCodes.REMINDER_NO_EMAIL));
        }
        audit.record(AuditEvent.of(MODULE, "reminder_sent", "Payment reminder for invoice " + i.getNumber())
                .entity("reminder_sent", r.getId()).detail("invoiceId", i.getId()));
        return InvoiceService.toReminder(r);
    }

    /** Every hour at minute 5 (when {@code rutera.scheduling.enabled}): {@link #runPlans(Instant)}. */
    @Scheduled(cron = "0 5 * * * *")
    public void hourly() {
        runPlans(Instant.now());
    }

    /**
     * The daily run: in every firma whose plan's hour has come (or passed, so a restart later in the day catches up),
     * the open sales invoices with reminders on get the reminder of each step that falls on today, once per step.
     * With {@code stopWhenPaid} a partly paid invoice gets no more plan reminders.
     */
    public void runPlans(Instant now) {
        List<Firma> active;
        try {
            active = RequestContext.runAsRoot(null, () -> tx.execute(s -> firmas.findAll().stream()
                    .filter(Firma::isActive).toList()));
        } catch (Exception e) {
            log.warn("Reminder run could not list the firmas: {}", e.toString());
            return;
        }
        for (Firma firma : active) {
            try {
                RequestContext.runAsFirma(firma.getId(), null, () -> tx.execute(s -> {
                    runPlan(firma, now);
                    return null;
                }));
            } catch (Exception e) {
                log.warn("Reminder run of firma {} failed: {}", firma.getId(), e.toString());
            }
        }
    }

    private void runPlan(Firma firma, Instant now) {
        ReminderSchedule plan = scheduleOf(firma.getId());
        ZonedDateTime local = now.atZone(zone(firma));
        if (local.getHour() < plan.getSendHour()) {
            return;
        }
        LocalDate today = local.toLocalDate();
        List<FinanceDto.Step> steps = json.readValue(plan.getSteps(), STEPS);
        for (Invoice i : invoices.openSalesWithReminders()) {
            if (plan.isStopWhenPaid() && i.getPaidAmount().signum() > 0) {
                continue;
            }
            for (FinanceDto.Step step : steps) {
                if (i.getDueDate().plusDays(step.offsetDays()).equals(today)
                        && !sent.existsByInvoiceIdAndOffsetDays(i.getId(), step.offsetDays())
                        && queue(i, step.offsetDays(), plan, step.escalate(), today) != null) {
                    log.info("Queued the payment reminder of invoice {} (step {} days)", i.getId(),
                            step.offsetDays());
                }
            }
        }
    }

    /** Queues the e-mail and records the reminder; null when the contractor has no invoice e-mail. */
    private ReminderSent queue(Invoice i, Integer offset, ReminderSchedule plan, boolean escalate, LocalDate today) {
        Contractor c = contractors.findById(i.getContractorId()).orElse(null);
        List<String> to = c == null || c.getInvoiceEmails() == null ? List.of() : json.readValue(c.getInvoiceEmails(),
                STRINGS);
        if (to.isEmpty()) {
            return null;
        }
        Firma firma = firmas.findById(i.getFirmaId()).orElseThrow();
        List<String> cc = new ArrayList<>(plan.getCcEmails() == null ? List.of()
                : json.readValue(plan.getCcEmails(), STRINGS));
        if (escalate && firma.getEmail() != null && !cc.contains(firma.getEmail())) {
            cc.add(firma.getEmail());
        }
        String language = c.getLang() != null ? c.getLang() : SupportedLanguages.normalize(firma.getLang());
        long overdueDays = ChronoUnit.DAYS.between(i.getDueDate(), today);
        String amount = i.openAmount().toPlainString() + " " + i.getCurrency();
        String subject = lang.get("finance.reminder.mail.subject", language, i.getNumber());
        String body = lang.get(overdueDays > 0 ? "finance.reminder.mail.overdue" : "finance.reminder.mail.before_due",
                language, i.getNumber(), amount, i.getDueDate().toString(), firma.getName(),
                String.valueOf(Math.max(0, overdueDays)));

        EmailOutbox mail = new EmailOutbox();
        mail.setFirmaId(i.getFirmaId());
        mail.setToEmails(String.join(",", to));
        mail.setCcEmails(cc.isEmpty() ? null : String.join(",", cc));
        mail.setSubject(truncate(subject, 255));
        mail.setTemplateKey(TEMPLATE);
        mail.setParams(json.writeValueAsString(Map.of("body", body, "invoiceId", i.getId())));
        mail.setStatus(EmailOutbox.Status.QUEUED);
        mail.setNextAttemptAt(Instant.now());
        mail = outbox.save(mail);

        ReminderSent r = new ReminderSent();
        r.setFirmaId(i.getFirmaId());
        r.setInvoiceId(i.getId());
        r.setOffsetDays(offset);
        r.setToEmails(truncate(String.join(",", to), 1000));
        r.setSubject(mail.getSubject());
        r.setStatus(ReminderSent.Status.QUEUED);
        r.setOutboxId(mail.getId());
        return sent.save(r);
    }

    // --- helpers -------------------------------------------------------------------------------------------

    /** The firma's default plan, created with the default steps on first use. */
    private ReminderSchedule scheduleOf(Long firmaId) {
        return schedules.findFirstByDefaultScheduleTrueOrderByIdAsc().orElseGet(() -> {
            ReminderSchedule s = new ReminderSchedule();
            s.setFirmaId(firmaId);
            s.setSteps(json.writeValueAsString(DEFAULT_STEPS));
            return schedules.save(s);
        });
    }

    private FinanceDto.Schedule toDto(ReminderSchedule s) {
        return new FinanceDto.Schedule(s.getSendHour(),
                s.getCcEmails() == null ? List.of() : json.readValue(s.getCcEmails(), STRINGS), s.isStopWhenPaid(),
                json.readValue(s.getSteps(), STEPS));
    }

    private static ZoneId zone(Firma firma) {
        try {
            return ZoneId.of(firma.getTimezone());
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

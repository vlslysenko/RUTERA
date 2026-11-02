package com.rutera.core.mail;

import com.rutera.common.context.RequestContext;
import com.rutera.common.domain.EmailOutbox;
import com.rutera.common.domain.ReminderSent;
import com.rutera.common.repository.EmailOutboxRepository;
import com.rutera.common.repository.ReminderSentRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Delivers the e-mails of {@code email_outbox} over SMTP (as SMDP's EmailService: {@link JavaMailSender}, UTF-8,
 * a configured sender address and name). Without {@code spring.mail.host} there is no sender and the e-mails stay
 * QUEUED until one is configured. A failed e-mail is retried after 5, 15, 45 and 135 minutes, then FAILED. The
 * reminder of an e-mail ({@link ReminderSent#getOutboxId()}) follows its status.
 * <p>
 * One application instance sends (standalone); several instances would need a claim step (status SENDING).
 */
@Component
public class OutboxSender {

    static final int MAX_ATTEMPTS = 5;
    private static final Logger log = LoggerFactory.getLogger(OutboxSender.class);
    private static final TypeReference<Map<String, Object>> PARAMS = new TypeReference<>() {
    };

    private final EmailOutboxRepository outbox;
    private final ReminderSentRepository reminders;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final String from;
    private final String fromName;

    public OutboxSender(EmailOutboxRepository outbox, ReminderSentRepository reminders,
                        ObjectProvider<JavaMailSender> mailSender, ObjectMapper json,
                        PlatformTransactionManager transactionManager,
                        @Value("${rutera.mail.from:${spring.mail.username:}}") String from,
                        @Value("${rutera.mail.from-name:RUTERA}") String fromName) {
        this.outbox = outbox;
        this.reminders = reminders;
        this.mailSender = mailSender;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
        this.from = from;
        this.fromName = fromName;
    }

    /** Every minute (when {@code rutera.scheduling.enabled}): the due e-mails, if SMTP is configured. */
    @Scheduled(fixedDelayString = "${rutera.mail.poll-interval:60s}", initialDelayString = "${rutera.mail.poll-interval:60s}")
    public void poll() {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender != null) {
            sendDue(sender, Instant.now());
        }
    }

    /** Sends the QUEUED e-mails whose next attempt is due at {@code now} (50 at most); returns how many went out. */
    public int sendDue(JavaMailSender sender, Instant now) {
        List<EmailOutbox> due;
        try {
            due = RequestContext.runAsRoot(null, () -> tx.execute(s -> outbox
                    .findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(EmailOutbox.Status.QUEUED,
                            now)));
        } catch (Exception e) {
            log.warn("Could not read the e-mail outbox: {}", e.toString());
            return 0;
        }
        int sent = 0;
        for (EmailOutbox mail : due) {
            String error = null;
            try {
                sender.send(message(sender, mail));
                sent++;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("E-mail {} ({}) failed: {}", mail.getId(), mail.getTemplateKey(), error);
            }
            String failure = error;
            try {
                RequestContext.runAsFirma(mail.getFirmaId(), null, () -> tx.execute(s -> {
                    record(mail.getId(), failure, now);
                    return null;
                }));
            } catch (Exception e) {
                log.warn("Could not record the result of e-mail {}: {}", mail.getId(), e.toString());
            }
        }
        return sent;
    }

    private MimeMessage message(JavaMailSender sender, EmailOutbox mail)
            throws MessagingException, UnsupportedEncodingException {
        MimeMessage mime = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
        if (!from.isBlank()) {
            helper.setFrom(new InternetAddress(from, fromName, "UTF-8"));
        }
        helper.setTo(addresses(mail.getToEmails()));
        if (mail.getCcEmails() != null && !mail.getCcEmails().isBlank()) {
            helper.setCc(addresses(mail.getCcEmails()));
        }
        helper.setSubject(mail.getSubject());
        Object body = json.readValue(mail.getParams(), PARAMS).get("body");
        helper.setText(body == null ? "" : body.toString(), false);
        return mime;
    }

    private static InternetAddress[] addresses(String list) throws AddressException {
        List<InternetAddress> result = new ArrayList<>();
        for (String part : list.split("[,;]")) {
            if (!part.isBlank()) {
                result.add(new InternetAddress(part.strip(), true));
            }
        }
        return result.toArray(InternetAddress[]::new);
    }

    private void record(Long id, String failure, Instant now) {
        EmailOutbox mail = outbox.findById(id).orElse(null);
        if (mail == null) {
            return;
        }
        mail.setAttempts(mail.getAttempts() + 1);
        if (failure == null) {
            mail.setStatus(EmailOutbox.Status.SENT);
            mail.setSentAt(now);
            mail.setError(null);
            mail.setNextAttemptAt(null);
        } else if (mail.getAttempts() >= MAX_ATTEMPTS) {
            mail.setStatus(EmailOutbox.Status.FAILED);
            mail.setError(failure);
            mail.setNextAttemptAt(null);
        } else {
            mail.setError(failure);
            mail.setNextAttemptAt(now.plus(Duration.ofMinutes(5L * (long) Math.pow(3, mail.getAttempts() - 1))));
        }
        outbox.save(mail);
        reminders.findByOutboxId(id).ifPresent(r -> {
            if (mail.getStatus() == EmailOutbox.Status.SENT) {
                r.setStatus(ReminderSent.Status.SENT);
                r.setSentAt(now);
                r.setError(null);
            } else if (mail.getStatus() == EmailOutbox.Status.FAILED) {
                r.setStatus(ReminderSent.Status.FAILED);
                r.setError(failure.length() > 500 ? failure.substring(0, 500) : failure);
            }
            reminders.save(r);
        });
    }
}

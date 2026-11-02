package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import com.rutera.core.finance.ReminderService;
import com.rutera.core.mail.OutboxSender;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invoices, payments, debts and payment reminders: line totals with VAT codes, the default sales numbering, orders
 * billed by an invoice, the status that follows payments and the due date, the debt buckets, the reminder plan run
 * and the delivery through the e-mail outbox. Every test runs in a firma of its own (time zone UTC).
 */
class FinanceTest extends AbstractIntegrationTest {

    private static final AtomicInteger FIRMAS = new AtomicInteger();

    @Autowired
    private ReminderService reminderService;
    @Autowired
    private OutboxSender outboxSender;

    private Firma firma;
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    private Client newFirmaOwner() throws Exception {
        int n = FIRMAS.incrementAndGet();
        firma = new Firma();
        firma.setName("Finance Test " + n);
        firma.setShortCode("FIN" + n);
        firma.setCountry("PL");
        firma.setLang("pl");
        firma.setEmail("biuro" + n + "@finance.example");
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-fin" + n + "@test.local", "FinOwner-1", Role.OWNER, firma.getId(), true);
        Client c = client();
        assertThat(c.login(owner.getLogin(), "FinOwner-1").getResponse().getStatus()).isEqualTo(200);
        return c;
    }

    private long id(MvcResult res) throws Exception {
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(201);
        return body(res).get("id").asLong();
    }

    private long customer(Client c, String name, List<String> emails, Integer paymentDays) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "country", "PL", "invoiceEmails", emails));
        if (paymentDays != null) {
            body.put("paymentDays", paymentDays);
        }
        return id(c.post("/app/contractors", body));
    }

    private long order(Client c, long customerId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("orderDate", today.minusDays(45).toString());
        body.put("customerId", customerId);
        body.put("price", 2500);
        body.put("currency", "EUR");
        body.put("loadingPlace", "Poznań");
        body.put("loadingCountry", "PL");
        body.put("unloadingPlace", "Berlin");
        body.put("unloadingCountry", "DE");
        return id(c.post("/app/orders", body));
    }

    private static Map<String, Object> line(String description, double qty, double price, String vatCode,
                                            Double vatRate) {
        Map<String, Object> l = new HashMap<>();
        l.put("description", description);
        l.put("qty", qty);
        l.put("unitPrice", price);
        l.put("vatCode", vatCode);
        l.put("vatRate", vatRate);
        return l;
    }

    private Map<String, Object> sales(long contractorId, LocalDate issueDate, List<Map<String, Object>> lines) {
        Map<String, Object> body = new HashMap<>();
        body.put("direction", "SALES");
        body.put("contractorId", contractorId);
        body.put("issueDate", issueDate.toString());
        body.put("currency", "eur");
        body.put("lines", lines);
        return body;
    }

    private static String series(LocalDate issueDate) {
        return String.format("FV/%d/%02d/", issueDate.getYear(), issueDate.getMonthValue());
    }

    @Test
    void anInvoiceBillsOrdersAndIsPaidInParts() throws Exception {
        Client c = newFirmaOwner();
        long customerId = customer(c, "Acme Food", List.of("ap@acme.example"), 30);
        long orderId = order(c, customerId);
        LocalDate issued = today.minusDays(40);

        Map<String, Object> body = sales(customerId, issued, List.of(
                line("Transport Poznań - Berlin", 1, 2500, "NP", null),
                line("Waiting time", 2, 100, "RATE", 23.0)));
        body.put("orderIds", List.of(orderId));
        MvcResult created = c.post("/app/invoices", body);
        long invoiceId = id(created);
        JsonNode inv = body(created);
        assertThat(inv.get("number").asString()).isEqualTo(series(issued) + "001");
        assertThat(inv.get("currency").asString()).isEqualTo("EUR");
        assertThat(inv.get("dueDate").asString()).as("payment days of the contractor")
                .isEqualTo(issued.plusDays(30).toString());
        assertThat(inv.get("net").asDouble()).isEqualTo(2700.0);
        assertThat(inv.get("vat").asDouble()).isEqualTo(46.0);
        assertThat(inv.get("gross").asDouble()).isEqualTo(2746.0);
        assertThat(inv.get("status").asString()).isEqualTo("OVERDUE");
        assertThat(inv.get("daysOverdue").asInt()).isEqualTo(10);
        assertThat(inv.get("lines")).hasSize(2);
        assertThat(inv.get("lines").get(0).get("vatCode").asString()).isEqualTo("NP");
        assertThat(inv.get("lines").get(0).get("vatRate").isNull()).isTrue();
        assertThat(inv.get("orders").get(0).get("id").asLong()).isEqualTo(orderId);
        assertThat(body(c.get("/app/orders/" + orderId)).get("status").asString()).isEqualTo("INVOICED");

        Map<String, Object> cancelOrder = new HashMap<>();
        JsonNode o = body(c.get("/app/orders/" + orderId));
        cancelOrder.put("orderDate", o.get("orderDate").asString());
        cancelOrder.put("customerId", customerId);
        cancelOrder.put("loadingPlace", "Poznań");
        cancelOrder.put("loadingCountry", "PL");
        cancelOrder.put("unloadingPlace", "Berlin");
        cancelOrder.put("unloadingCountry", "DE");
        cancelOrder.put("cancelled", true);
        assertThat(c.put("/app/orders/" + orderId, cancelOrder).getResponse().getStatus())
                .as("an invoiced order cannot be cancelled").isEqualTo(409);

        Map<String, Object> second = sales(customerId, issued, List.of(line("Extra", 1, 50, "ZW", null)));
        assertThat(body(c.post("/app/invoices", second)).get("number").asString()).isEqualTo(series(issued) + "002");
        second.put("number", series(issued) + "001");
        MvcResult taken = c.post("/app/invoices", second);
        assertThat(taken.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(taken).get("details").get("number").asString()).isEqualTo("finance.invoice.number_taken");

        JsonNode debts = body(c.get("/app/debts"));
        assertThat(debts).hasSize(1);
        assertThat(debts.get(0).get("contractor").get("name").asString()).isEqualTo("Acme Food");
        assertThat(debts.get(0).get("open").asDouble()).isEqualTo(2796.0);
        assertThat(debts.get(0).get("overdue").asDouble()).isEqualTo(2796.0);
        assertThat(debts.get(0).get("invoices").asInt()).isEqualTo(2);
        assertThat(debts.get(0).get("buckets").get("d1to30").asDouble()).isEqualTo(2796.0);

        MvcResult paid = c.post("/app/invoices/" + invoiceId + "/payments",
                Map.of("paidAt", today.toString(), "amount", 1000, "reference", "PRZELEW 1"));
        assertThat(id(paid)).isPositive();
        assertThat(body(paid).get("method").asString()).isEqualTo("TRANSFER");
        MvcResult tooMuch = c.post("/app/invoices/" + invoiceId + "/payments",
                Map.of("paidAt", today.toString(), "amount", 1746.01));
        assertThat(tooMuch.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(tooMuch).get("details").get("amount").asString()).isEqualTo("finance.payment.too_much");
        id(c.post("/app/invoices/" + invoiceId + "/payments",
                Map.of("paidAt", today.toString(), "amount", 1746, "method", "CASH")));

        JsonNode detail = body(c.get("/app/invoices/" + invoiceId));
        assertThat(detail.get("status").asString()).isEqualTo("PAID");
        assertThat(detail.get("openAmount").asDouble()).isEqualTo(0.0);
        assertThat(detail.get("payments")).hasSize(2);
        assertThat(c.delete("/app/invoices/" + invoiceId).getResponse().getStatus())
                .as("an invoice with payments stays").isEqualTo(409);

        JsonNode page = body(c.get("/app/invoices?status=OVERDUE"));
        assertThat(page.get("total").asLong()).isEqualTo(1);
        assertThat(page.get("totals").get(0).get("open").asDouble()).isEqualTo(50.0);
        assertThat(body(c.get("/app/invoices?q=acme")).get("total").asLong()).isEqualTo(2);

        long paymentId = detail.get("payments").get(1).get("id").asLong();
        assertThat(c.delete("/app/invoices/" + invoiceId + "/payments/" + paymentId).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(body(c.get("/app/invoices/" + invoiceId)).get("status").asString()).isEqualTo("OVERDUE");
    }

    @Test
    void purchaseInvoicesNeedTheSupplierNumberAndHeaderTotals() throws Exception {
        Client c = newFirmaOwner();
        long supplierId = customer(c, "Fuel Supplier", List.of(), null);
        Map<String, Object> body = new HashMap<>();
        body.put("direction", "PURCHASE");
        body.put("contractorId", supplierId);
        body.put("issueDate", today.toString());
        body.put("currency", "PLN");
        body.put("net", 100);
        body.put("gross", 123);
        MvcResult noNumber = c.post("/app/invoices", body);
        assertThat(noNumber.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(noNumber).get("details").get("number").asString()).isEqualTo("validation.required");

        body.put("number", "FS 77/2026");
        MvcResult created = c.post("/app/invoices", body);
        long id = id(created);
        assertThat(body(created).get("vat").asDouble()).isEqualTo(23.0);
        assertThat(body(created).get("remindersOn").asBoolean()).isFalse();
        assertThat(body(created).get("dueDate").asString()).isEqualTo(today.plusDays(14).toString());

        Map<String, Object> bad = new HashMap<>(body);
        bad.put("number", "FS 78/2026");
        bad.put("lines", List.of(line("Diesel", 100, 6.5, "RATE", null)));
        bad.put("orderIds", List.of(order(c, supplierId)));
        MvcResult invalid = c.post("/app/invoices", bad);
        assertThat(invalid.getResponse().getStatus()).isEqualTo(422);
        JsonNode details = body(invalid).get("details");
        assertThat(details.get("lines[0].vatRate").asString()).isEqualTo("validation.required");
        assertThat(details.get("orderIds").asString()).as("only sales invoices bill orders")
                .isEqualTo("validation.invalid_value");

        body.put("cancelled", true);
        JsonNode cancelled = body(c.put("/app/invoices/" + id, body));
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("openAmount").asDouble()).isEqualTo(0.0);
        assertThat(c.post("/app/invoices/" + id + "/payments", Map.of("paidAt", today.toString(), "amount", 1))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(c.delete("/app/invoices/" + id).getResponse().getStatus()).isEqualTo(204);
        assertThat(c.get("/app/invoices/" + id).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void thePlanQueuesRemindersAndTheOutboxSendsThem() throws Exception {
        Client c = newFirmaOwner();
        long customerId = customer(c, "Slow Payer", List.of("ksiegowosc@slow.example"), 14);
        long noMailId = customer(c, "No Mail Ltd", List.of(), 14);
        LocalDate issued = today.minusDays(15);
        long invoiceId = id(c.post("/app/invoices", sales(customerId, issued, List.of(
                line("Transport", 1, 1000, "RATE", 23.0)))));
        long noMailInvoice = id(c.post("/app/invoices", sales(noMailId, issued, List.of(
                line("Transport", 1, 500, "RATE", 23.0)))));

        JsonNode plan = body(c.get("/app/reminders/schedule"));
        assertThat(plan.get("sendHour").asInt()).isEqualTo(9);
        assertThat(plan.get("steps")).hasSize(5);

        Instant early = today.atTime(8, 0).toInstant(ZoneOffset.UTC);
        reminderService.runPlans(early);
        assertThat(body(c.get("/app/invoices/" + invoiceId + "/reminders"))).as("before the plan's hour").isEmpty();

        Instant morning = today.atTime(10, 0).toInstant(ZoneOffset.UTC);
        reminderService.runPlans(morning);
        reminderService.runPlans(morning);
        JsonNode reminders = body(c.get("/app/invoices/" + invoiceId + "/reminders"));
        assertThat(reminders).as("step +1 day, once").hasSize(1);
        assertThat(reminders.get(0).get("offsetDays").asInt()).isEqualTo(1);
        assertThat(reminders.get(0).get("status").asString()).isEqualTo("QUEUED");
        assertThat(reminders.get(0).get("toEmails").get(0).asString()).isEqualTo("ksiegowosc@slow.example");
        assertThat(body(c.get("/app/invoices/" + noMailInvoice + "/reminders"))).isEmpty();

        MvcResult noMail = c.post("/app/invoices/" + noMailInvoice + "/reminders", Map.of());
        assertThat(noMail.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(noMail).get("details").get("contractor").asString())
                .isEqualTo("finance.reminder.no_email");
        MvcResult byHand = c.post("/app/invoices/" + invoiceId + "/reminders", Map.of());
        assertThat(id(byHand)).isPositive();
        assertThat(body(byHand).get("offsetDays").isNull()).isTrue();

        List<MimeMessage> delivered = new ArrayList<>();
        JavaMailSenderImpl failing = new JavaMailSenderImpl() {
            @Override
            protected void doSend(MimeMessage[] messages, Object[] originals) {
                throw new MailSendException("SMTP down");
            }
        };
        JavaMailSenderImpl working = new JavaMailSenderImpl() {
            @Override
            protected void doSend(MimeMessage[] messages, Object[] originals) {
                delivered.addAll(List.of(messages));
            }
        };
        // the e-mails were queued at the real time: send relative to it
        outboxSender.sendDue(failing, Instant.now().plus(1, ChronoUnit.MINUTES));
        JsonNode afterFailure = body(c.get("/app/invoices/" + invoiceId + "/reminders"));
        assertThat(afterFailure.get(0).get("status").asString()).as("retried later").isEqualTo("QUEUED");

        outboxSender.sendDue(working, Instant.now().plus(10, ChronoUnit.MINUTES));
        JsonNode sent = body(c.get("/app/invoices/" + invoiceId + "/reminders"));
        assertThat(sent).allSatisfy(r -> assertThat(r.get("status").asString()).isEqualTo("SENT"));
        assertThat(body(c.get("/app/invoices/" + invoiceId)).get("lastReminderAt").isNull()).isFalse();

        String number = body(c.get("/app/invoices/" + invoiceId)).get("number").asString();
        List<MimeMessage> ours = delivered.stream().filter(m -> subject(m).contains(number)).toList();
        assertThat(ours).hasSize(2);
        MimeMessage mail = ours.getFirst();
        assertThat(subject(mail)).isEqualTo("Przypomnienie o płatności: faktura " + number);
        String text = mail.getContent().toString();
        assertThat(text).contains(number).contains("1230.00 EUR").contains("Finance Test")
                .contains(issued.plusDays(14).toString());
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo("ksiegowosc@slow.example");
    }

    @Test
    void thePlanCanBeChangedAndFinanceNeedsItsPrivileges() throws Exception {
        Client c = newFirmaOwner();
        Map<String, Object> plan = new HashMap<>();
        plan.put("sendHour", 7);
        plan.put("ccEmails", List.of("windykacja@finance.example"));
        plan.put("stopWhenPaid", false);
        plan.put("steps", List.of(Map.of("offsetDays", 10, "escalate", true), Map.of("offsetDays", -2)));
        JsonNode saved = body(c.put("/app/reminders/schedule", plan));
        assertThat(saved.get("sendHour").asInt()).isEqualTo(7);
        assertThat(saved.get("stopWhenPaid").asBoolean()).isFalse();
        assertThat(saved.get("steps").get(0).get("offsetDays").asInt()).as("sorted").isEqualTo(-2);
        assertThat(body(c.get("/app/reminders/schedule")).get("ccEmails").get(0).asString())
                .isEqualTo("windykacja@finance.example");

        plan.put("sendHour", 25);
        MvcResult bad = c.put("/app/reminders/schedule", plan);
        assertThat(bad.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(bad).get("details").get("sendHour").asString()).isEqualTo("validation.invalid_value");

        AppUser dispatcher = createUser("dispatcher-fin" + firma.getId() + "@test.local", "FinDisp-1",
                Role.DISPATCHER, firma.getId(), true);
        Client d = client();
        assertThat(d.login(dispatcher.getLogin(), "FinDisp-1").getResponse().getStatus()).isEqualTo(200);
        assertThat(d.get("/app/invoices").getResponse().getStatus()).isEqualTo(403);
        assertThat(d.get("/app/debts").getResponse().getStatus()).isEqualTo(403);

        AppUser accountant = createUser("accountant-fin" + firma.getId() + "@test.local", "FinAcc-1",
                Role.ACCOUNTANT, firma.getId(), true);
        Client a = client();
        assertThat(a.login(accountant.getLogin(), "FinAcc-1").getResponse().getStatus()).isEqualTo(200);
        assertThat(a.get("/app/invoices").getResponse().getStatus()).isEqualTo(200);
    }

    private static String subject(MimeMessage m) {
        try {
            return m.getSubject();
        } catch (MessagingException e) {
            throw new IllegalStateException(e);
        }
    }
}

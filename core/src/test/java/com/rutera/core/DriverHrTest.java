package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Driver HR: absences, the working-day calendar, pay rates, payments and their privileges. */
class DriverHrTest extends AbstractIntegrationTest {

    private static final AtomicInteger FIRMAS = new AtomicInteger();

    private Firma firma;

    private Client newFirmaOwner() throws Exception {
        int n = FIRMAS.incrementAndGet();
        firma = new Firma();
        firma.setName("Hr Test " + n);
        firma.setShortCode("HR" + n);
        firma.setCountry("PL");
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-hr" + n + "@test.local", "HrOwner-123", Role.OWNER, firma.getId(), true);
        Client c = client();
        assertThat(c.login(owner.getLogin(), "HrOwner-123").getResponse().getStatus()).isEqualTo(200);
        return c;
    }

    private long driver(Client c, String first, String last) throws Exception {
        MvcResult res = c.post("/app/drivers", Map.of("firstName", first, "lastName", last));
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        return body(res).get("id").asLong();
    }

    private static Map<String, Object> absence(String kind, String from, String to) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        body.put("fromDate", from);
        body.put("toDate", to);
        return body;
    }

    @Test
    void absencesDoNotOverlapAndShowInTheCalendar() throws Exception {
        Client c = newFirmaOwner();
        long anna = driver(c, "Anna", "Nowak");
        long piotr = driver(c, "Piotr", "Lis");

        MvcResult created = c.post("/app/drivers/" + anna + "/absences", absence("VACATION", "2026-07-06", "2026-07-17"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode vacation = body(created);
        long vacationId = vacation.get("id").asLong();
        assertThat(vacation.get("days").asInt()).isEqualTo(12);
        assertThat(vacation.get("workingDays").asInt()).isEqualTo(10);
        assertThat(vacation.get("paid").asBoolean()).isTrue();
        assertThat(vacation.get("approvedBy").get("name").asString()).isNotBlank();
        assertThat(vacation.get("driver").get("name").asString()).isEqualTo("Anna Nowak");

        MvcResult overlap = c.post("/app/drivers/" + anna + "/absences", absence("SICK", "2026-07-17", "2026-07-20"));
        assertThat(overlap.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(overlap).get("details").get("toDate").asString()).isEqualTo("driver.absence.overlap");
        MvcResult backwards = c.post("/app/drivers/" + anna + "/absences", absence("SICK", "2026-07-20", "2026-07-19"));
        assertThat(body(backwards).get("details").get("toDate").asString()).isEqualTo("validation.invalid_value");
        MvcResult unpaid = c.post("/app/drivers/" + piotr + "/absences", absence("UNPAID", "2026-07-10", "2026-07-10"));
        assertThat(body(unpaid).get("paid").asBoolean()).isFalse();

        assertThat(body(c.get("/app/absences?from=2026-07-01&to=2026-07-31"))).hasSize(2);
        assertThat(body(c.get("/app/absences?from=2026-07-01&to=2026-07-31&kind=VACATION"))).hasSize(1);
        assertThat(body(c.get("/app/drivers/" + anna + "/absences"))).hasSize(1);

        Map<String, Object> shorter = absence("VACATION", "2026-07-06", "2026-07-10");
        shorter.put("note", "Moved one week");
        assertThat(body(c.put("/app/drivers/" + anna + "/absences/" + vacationId, shorter)).get("days").asInt())
                .isEqualTo(5);

        JsonNode month = body(c.get("/app/drivers/" + anna + "/days?month=2026-07"));
        assertThat(month.get("days")).hasSize(31);
        JsonNode july6 = month.get("days").get(5);
        assertThat(july6.get("day").asString()).isEqualTo("2026-07-06");
        assertThat(july6.get("kind").asString()).isEqualTo("ABSENCE");
        assertThat(july6.get("source").asString()).isEqualTo("ABSENCE");
        assertThat(july6.get("absenceKind").asString()).isEqualTo("VACATION");
        assertThat(month.get("days").get(0).get("source").asString()).isEqualTo("NONE");
        assertThat(month.get("summary").get("absenceDays").asInt()).isEqualTo(5);

        MvcResult manual = c.put("/app/drivers/" + anna + "/days/2026-07-01", Map.of("kind", "WORK", "note", "Office"));
        assertThat(body(manual).get("kind").asString()).isEqualTo("WORK");
        assertThat(body(manual).get("source").asString()).isEqualTo("MANUAL");
        assertThat(body(c.get("/app/drivers/" + anna + "/days?month=2026-07")).get("summary").get("workDays").asInt())
                .isEqualTo(1);
        assertThat(body(c.delete("/app/drivers/" + anna + "/days/2026-07-01")).get("source").asString())
                .isEqualTo("NONE");
        assertThat(body(c.get("/app/drivers/" + anna + "/days?month=2026-13")).get("details").get("month").asString())
                .isEqualTo("validation.invalid_format");

        assertThat(c.delete("/app/drivers/" + anna + "/absences/" + vacationId).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(body(c.get("/app/drivers/" + anna + "/absences"))).isEmpty();
    }

    @Test
    void payRatesAndPaymentsWithTotals() throws Exception {
        Client c = newFirmaOwner();
        long anna = driver(c, "Anna", "Nowak");
        String rates = "/app/drivers/" + anna + "/pay-rates";
        Map<String, Object> rate = new HashMap<>(Map.of("validFrom", "2026-01-01", "monthlyBase", 4666,
                "perDiemAbroad", 55, "perKm", 0.0425, "currency", "pln"));
        MvcResult created = c.post(rates, rate);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(created).get("currency").asString()).isEqualTo("PLN");
        assertThat(body(created).get("perKm").decimalValue()).isEqualByComparingTo("0.0425");
        assertThat(body(c.post(rates, rate)).get("details").get("validFrom").asString())
                .isEqualTo("driver.pay_rate.date_taken");
        assertThat(body(c.post(rates, Map.of("validFrom", "2026-06-01", "currency", "PLN"))).get("details")
                .get("monthlyBase").asString()).isEqualTo("validation.required");
        rate.put("validFrom", "2026-07-01");
        rate.put("monthlyBase", 5000);
        c.post(rates, rate);
        JsonNode list = body(c.get(rates));
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("validFrom").asString()).isEqualTo("2026-07-01");

        String payments = "/app/drivers/" + anna + "/payments";
        c.post(payments, Map.of("period", "2026-07", "kind", "SALARY", "amount", 5000, "currency", "PLN"));
        c.post(payments, Map.of("period", "2026-07", "kind", "PER_DIEM", "amount", 330, "currency", "EUR"));
        MvcResult advance = c.post(payments, Map.of("period", "2026-07", "kind", "ADVANCE", "amount", 1000,
                "currency", "PLN", "paidAt", "2026-07-15"));
        long advanceId = body(advance).get("id").asLong();
        c.post(payments, Map.of("period", "2026-06", "kind", "SALARY", "amount", 4800, "currency", "PLN"));
        MvcResult bad = c.post(payments, Map.of("period", "2026/07", "kind", "TIP", "amount", 0, "currency", "PLN"));
        JsonNode details = body(bad).get("details");
        assertThat(details.get("amount").asString()).isEqualTo("validation.invalid_value");

        MvcResult badPeriod = c.post(payments, Map.of("period", "2026/07", "kind", "TIP", "amount", 10,
                "currency", "PLN"));
        assertThat(body(badPeriod).get("details").get("period").asString()).isEqualTo("validation.invalid_format");
        assertThat(body(badPeriod).get("details").get("kind").asString()).isEqualTo("validation.invalid_value");

        assertThat(body(c.get(payments + "?from=2026-07&to=2026-07"))).hasSize(3);
        JsonNode july = body(c.get("/app/payments?period=2026-07"));
        assertThat(july.get("items")).hasSize(3);
        assertThat(july.get("totals")).hasSize(2);
        assertThat(july.get("totals").get(0).get("currency").asString()).isEqualTo("EUR");
        assertThat(july.get("totals").get(0).get("amount").decimalValue()).isEqualByComparingTo("330");
        assertThat(july.get("totals").get(1).get("amount").decimalValue()).as("salary minus advance")
                .isEqualByComparingTo("4000");

        assertThat(c.delete(payments + "/" + advanceId).getResponse().getStatus()).isEqualTo(204);
        assertThat(body(c.get("/app/payments?period=2026-07")).get("totals").get(1).get("amount").decimalValue())
                .isEqualByComparingTo("5000");
    }

    @Test
    void paymentsAreForOwnerAndAccountantOnly() throws Exception {
        Client c = newFirmaOwner();
        long anna = driver(c, "Anna", "Nowak");
        c.post("/app/drivers/" + anna + "/payments", Map.of("period", "2026-07", "kind", "SALARY", "amount", 5000,
                "currency", "PLN"));

        AppUser dispatcher = createUser("dispatcher-hr" + firma.getId() + "@test.local", "Disp-Pass-123",
                Role.DISPATCHER, firma.getId(), true);
        Client disp = client();
        disp.login(dispatcher.getLogin(), "Disp-Pass-123");
        assertThat(disp.get("/app/drivers/" + anna + "/payments").getResponse().getStatus()).isEqualTo(403);
        assertThat(disp.get("/app/payments").getResponse().getStatus()).isEqualTo(403);
        assertThat(disp.get("/app/drivers/" + anna + "/days?month=2026-07").getResponse().getStatus()).isEqualTo(200);
        assertThat(disp.post("/app/drivers/" + anna + "/absences", absence("SICK", "2026-08-03", "2026-08-05"))
                .getResponse().getStatus()).isEqualTo(201);

        AppUser accountant = createUser("accountant-hr" + firma.getId() + "@test.local", "Acct-Pass-789",
                Role.ACCOUNTANT, firma.getId(), true);
        Client acct = client();
        acct.login(accountant.getLogin(), "Acct-Pass-789");
        assertThat(body(acct.get("/app/payments?period=2026-07")).get("items")).hasSize(1);
        assertThat(acct.post("/app/drivers/" + anna + "/absences", absence("SICK", "2026-09-01", "2026-09-02"))
                .getResponse().getStatus()).isEqualTo(403);

        Client other = newFirmaOwner();
        assertThat(other.get("/app/drivers/" + anna + "/payments").getResponse().getStatus()).isEqualTo(404);
        assertThat(body(other.get("/app/payments")).get("items")).isEmpty();
    }
}

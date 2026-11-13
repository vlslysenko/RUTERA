package com.rutera.core;

import com.rutera.common.context.RequestContext;
import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.DriverViolation;
import com.rutera.common.domain.Firma;
import com.rutera.common.repository.DriverExplanationRepository;
import com.rutera.common.repository.DriverViolationRepository;
import com.rutera.common.security.Role;
import com.rutera.core.explain.ExplanationService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drivers' explanations: asked by the office (by hand and from a violation) and by the import, signed by the driver
 * with a reason and a signature, the reasons with their Article 12 options, the note for the printout, an Article 12
 * claim, the first version's reason codes, the PDF in the language of the firma's country, the violation list's
 * link, the driver's own scope, and the firma settings (country of registration, automatic explanations).
 */
class ExplanationTest extends AbstractIntegrationTest {

    @Autowired
    private DriverViolationRepository violations;
    @Autowired
    private DriverExplanationRepository explanations;
    @Autowired
    private ExplanationService service;

    private static String signaturePng() throws Exception {
        BufferedImage image = new BufferedImage(300, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.drawLine(10, 80, 290, 20);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private DriverViolation violation(long firmaId, long driverId, Instant start) throws Exception {
        DriverViolation v = new DriverViolation();
        v.setFirmaId(firmaId);
        v.setDriverId(driverId);
        v.setRule(DriverViolation.Rule.DAILY_DRIVING_9H);
        v.setSeverity(DriverViolation.Severity.MINOR);
        v.setStartAt(start);
        v.setEndAt(start.plus(Duration.ofHours(14)));
        v.setActualValue(585);
        v.setLimitValue(540);
        v.setVehiclePlate("EX 1001");
        return RequestContext.runAsFirma(firmaId, null, () -> violations.save(v));
    }

    /** The PDF's text with every run of white space (line breaks included) as one space. */
    private static String pdfText(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
        }
    }

    private static JsonNode reason(JsonNode options, String code) {
        for (JsonNode o : options) {
            if (o.get("code").asString().equals(code)) {
                return o;
            }
        }
        throw new AssertionError("no reason " + code);
    }

    @Test
    void askSignAndPrint() throws Exception {
        Firma firma = new Firma();
        firma.setName("Explain Trans Sp. z o.o.");
        firma.setShortCode("EXPL1");
        firma.setCountry("PL");
        firma.setTimezone("Europe/Warsaw");
        firma.setAddress("ul. Testowa 1\n60-001 Poznań");
        long firmaId = firmas.save(firma).getId();
        AppUser owner = createUser("owner-explain@test.local", "ExplainOwner-1", Role.OWNER, firmaId, true);
        AppUser login = createUser("driver-explain@test.local", "ExplainDriver-1", Role.DRIVER, firmaId, true);
        AppUser other = createUser("other-explain@test.local", "ExplainOther-1", Role.DRIVER, firmaId, true);
        Client office = client();
        assertThat(office.login(owner.getLogin(), "ExplainOwner-1").getResponse().getStatus()).isEqualTo(200);
        long driverId = body(office.post("/app/drivers", Map.of("firstName", "Jan", "lastName", "Wyjaśniający",
                "userId", login.getId(), "cardNumber", "PL1111222233334444", "cardIssuingCountry", "PL")))
                .get("id").asLong();
        long otherId = body(office.post("/app/drivers", Map.of("firstName", "Ewa", "lastName", "Inna",
                "userId", other.getId()))).get("id").asLong();
        long vehicleId = body(office.post("/app/fleet/vehicles", Map.of("kind", "TRACTOR", "plate", "EX 1001")))
                .get("id").asLong();

        // The firma tab: country of registration and the language of explanations.
        JsonNode settings = body(office.get("/app/admin/firma"));
        assertThat(settings.get("country").asString()).isEqualTo("PL");
        assertThat(settings.get("explanationLang").asString()).isEqualTo("pl");
        assertThat(settings.get("explanationsAuto").asBoolean()).isTrue();
        assertThat(settings.get("explanationsDays").asInt()).isEqualTo(28);

        // By hand.
        Map<String, Object> manual = new HashMap<>(Map.of("driverId", driverId, "vehicleId", vehicleId,
                "ruleCode", "DAILY_DRIVING_9H", "startedAt", "2026-11-10T04:00:00Z",
                "endedAt", "2026-11-10T18:30:00Z", "exceededMin", 30));
        MvcResult created = office.post("/app/explanations", manual);
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode e = body(created);
        long id = e.get("id").asLong();
        assertThat(e.get("status").asString()).isEqualTo("PENDING");
        assertThat(e.get("source").asString()).isEqualTo("MANUAL");
        assertThat(e.get("docLang").asString()).isEqualTo("pl");
        assertThat(e.get("limitMin").asInt()).as("the rule's limit").isEqualTo(540);
        assertThat(e.get("actualMin").asInt()).isEqualTo(570);
        assertThat(e.get("vehicle").get("plate").asString()).isEqualTo("EX 1001");
        assertThat(e.get("vehicle").get("id").asLong()).isEqualTo(vehicleId);
        assertThat(e.get("createdBy").get("id").asLong()).isEqualTo(owner.getId());
        assertThat(office.post("/app/explanations", manual).getResponse().getStatus()).as("duplicate").isEqualTo(409);
        manual.put("ruleCode", "NOT_A_RULE");
        assertThat(body(office.post("/app/explanations", manual)).get("details").get("ruleCode").asString())
                .isEqualTo("validation.invalid_value");

        // The driver sees only their own.
        Client phone = client();
        assertThat(phone.login(login.getLogin(), "ExplainDriver-1").getResponse().getStatus()).isEqualTo(200);
        Client otherPhone = client();
        assertThat(otherPhone.login(other.getLogin(), "ExplainOther-1").getResponse().getStatus()).isEqualTo(200);
        assertThat(body(phone.get("/app/explanations")).get("total").asLong()).isEqualTo(1);
        assertThat(body(phone.get("/app/explanations?driverId=" + otherId)).get("total").asLong())
                .as("a driver's filter is ignored").isEqualTo(1);
        assertThat(body(otherPhone.get("/app/explanations")).get("total").asLong()).isZero();
        assertThat(otherPhone.get("/app/explanations/" + id).getResponse().getStatus()).isEqualTo(403);
        assertThat(phone.post("/app/explanations", manual).getResponse().getStatus()).as("office only").isEqualTo(403);
        MvcResult preview = phone.get("/app/explanations/" + id + "/pdf");
        assertThat(preview.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(pdfText(preview.getResponse().getContentAsByteArray())).contains("PROJEKT");

        // Signing: a reason, a text for OTHER, a PNG signature; only once and only by the driver.
        String signature = signaturePng();
        Map<String, Object> sign = new HashMap<>(Map.of("reason", "OTHER", "signature", signature));
        assertThat(body(phone.post("/app/explanations/" + id + "/sign", sign)).get("details").get("reasonText")
                .asString()).isEqualTo("validation.required");
        sign.put("reason", "TRAFFIC_JAM");
        sign.put("signature", "data:image/png;base64,bm90IGEgcG5n");
        assertThat(body(phone.post("/app/explanations/" + id + "/sign", sign)).get("details").get("signature")
                .asString()).isEqualTo("validation.invalid_value");
        sign.put("signature", signature);
        sign.put("place", "Poznań");
        sign.put("reasonText", "Korek na A2 po wypadku, brak możliwości zjazdu.");
        assertThat(otherPhone.post("/app/explanations/" + id + "/sign", sign).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(office.post("/app/explanations/" + id + "/sign", sign).getResponse().getStatus())
                .as("the office cannot sign for the driver").isEqualTo(403);
        JsonNode signed = body(phone.post("/app/explanations/" + id + "/sign", sign));
        assertThat(signed.get("status").asString()).isEqualTo("SIGNED");
        assertThat(signed.get("reason").asString()).isEqualTo("TRAFFIC_JAM");
        assertThat(signed.get("hasPdf").asBoolean()).isTrue();
        assertThat(signed.get("signedBy").get("id").asLong()).isEqualTo(login.getId());
        MvcResult again = phone.post("/app/explanations/" + id + "/sign", sign);
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(again).get("code").asString()).isEqualTo("explanation.not_pending");
        String text = pdfText(office.get("/app/explanations/" + id + "/pdf").getResponse().getContentAsByteArray());
        assertThat(text).contains("WYJAŚNIENIE KIEROWCY").contains("Jan Wyjaśniający").contains("EX 1001")
                .contains("PL1111222233334444").contains("Explain Trans Sp. z o.o.").contains("10.11.2026")
                .contains("Przyczyną był zator drogowy (korek).").contains("Korek na A2").contains("Poznań, dnia")
                .contains("wyniósł 9 godz. 30 min przy normie 9 godz. (przekroczenie o 30 min)")
                .contains("Nie powołuję się na art. 12")
                .contains("ul. Testowa 1, 60-001 Poznań").doesNotContain("PROJEKT");
        assertThat(signed.get("art12").asString()).isEqualTo("NONE");
        assertThat(signed.get("infringementCode").asString()).isEqualTo("DAILY_DRIVING_EXCEEDED");
        assertThat(signed.get("art12Allowed").get("safety").asBoolean()).isTrue();
        assertThat(signed.get("art12Allowed").get("home").asBoolean()).isTrue();

        // The reasons and what each allows (Article 12 of Regulation 561/2006).
        JsonNode daily = body(phone.get("/app/explanations/reasons?ruleCode=DAILY_DRIVING_9H"));
        assertThat(daily.size()).isEqualTo(19);
        assertThat(reason(daily, "TRAFFIC_JAM").get("art12Safety").asBoolean()).isFalse();
        assertThat(reason(daily, "NO_PARKING_SPACE").get("art12Safety").asBoolean()).isTrue();
        assertThat(reason(daily, "NO_PARKING_SPACE").get("textRequired").asBoolean()).isTrue();
        assertThat(reason(daily, "REACH_HOME_OR_BASE").get("art12Home").asBoolean()).isTrue();
        JsonNode breaks = body(office.get("/app/explanations/reasons?ruleCode=BREAK_45_AFTER_4H30"));
        assertThat(reason(breaks, "REACH_HOME_OR_BASE").get("art12Home").asBoolean()).isFalse();
        assertThat(reason(breaks, "TRAFFIC_ACCIDENT").get("art12Safety").asBoolean()).isTrue();
        assertThat(reason(body(phone.get("/app/explanations/reasons")), "SECURITY_THREAT").get("art12Safety")
                .asBoolean()).isTrue();
        assertThat(body(phone.get("/app/explanations/reasons?ruleCode=NOT_A_RULE")).get("details").get("ruleCode")
                .asString()).isEqualTo("validation.invalid_value");

        // From a violation: its data is copied and the violation list links the explanation.
        DriverViolation v = violation(firmaId, driverId, Instant.now().minus(Duration.ofDays(3)));
        JsonNode fromViolation = body(office.post("/app/explanations", Map.of("violationId", v.getId())));
        assertThat(fromViolation.get("violationId").asLong()).isEqualTo(v.getId());
        assertThat(fromViolation.get("exceededMin").asInt()).isEqualTo(45);
        assertThat(fromViolation.get("limitMin").asInt()).isEqualTo(540);
        JsonNode listed = body(office.get("/app/tacho/violations?driverId=" + driverId)).get("items").get(0);
        assertThat(listed.get("explanationId").asLong()).isEqualTo(fromViolation.get("id").asLong());
        assertThat(listed.get("explanationStatus").asString()).isEqualTo("PENDING");
        long fromId = fromViolation.get("id").asLong();
        assertThat(body(office.post("/app/explanations/" + fromId + "/remind", null)).get("status").asString())
                .isEqualTo("PENDING");
        assertThat(body(office.post("/app/explanations/" + fromId + "/cancel", null)).get("status").asString())
                .isEqualTo("CANCELLED");
        assertThat(office.post("/app/explanations/" + fromId + "/remind", null).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(body(office.post("/app/explanations", Map.of("violationId", v.getId()))).get("status").asString())
                .as("asking again reopens it").isEqualTo("PENDING");

        // Article 12: the note for the printout, then signing with a claim.
        JsonNode note = body(phone.get("/app/explanations/" + fromId
                + "/printout-note?reason=NO_PARKING_SPACE&art12=SAFETY&place=MOP Września"));
        assertThat(note.get("lang").asString()).isEqualTo("pl");
        assertThat(note.get("instruction").asString()).contains("wydruku");
        assertThat(note.get("text").asString()).contains("Art. 12").contains("brak wolnych miejsc na parkingu")
                .contains("45 min").contains("MOP Września").contains("Jan Wyjaśniający")
                .contains("PL1111222233334444").contains("EX 1001");
        assertThat(body(phone.get("/app/explanations/" + fromId + "/printout-note?reason=REACH_HOME_OR_BASE"))
                .get("text").asString()).contains("odpoczynek tygodniowy");
        assertThat(otherPhone.get("/app/explanations/" + fromId + "/printout-note").getResponse().getStatus())
                .isEqualTo(403);
        Map<String, Object> claim = new HashMap<>(Map.of("reason", "TRAFFIC_JAM", "signature", signature,
                "art12", "SAFETY"));
        JsonNode refused = body(phone.post("/app/explanations/" + fromId + "/sign", claim)).get("details");
        assertThat(refused.get("art12").asString()).as("a traffic jam is no Article 12 reason")
                .isEqualTo("validation.invalid_value");
        assertThat(refused.get("art12Place").asString()).isEqualTo("validation.required");
        assertThat(refused.get("printoutNote").asString()).isEqualTo("validation.required");
        claim.put("reason", "REACH_HOME_OR_BASE");
        claim.put("art12", "HOME_2H");
        claim.put("art12Place", "Baza Poznań");
        claim.put("printoutNote", true);
        claim.put("printoutNoteAt", Instant.now().minus(Duration.ofDays(2)).toString());
        claim.put("art12BreakFrom", "25:00");
        refused = body(phone.post("/app/explanations/" + fromId + "/sign", claim)).get("details");
        assertThat(refused.get("art12BreakFrom").asString()).isEqualTo("validation.invalid_format");
        assertThat(refused.get("art12BreakTo").asString()).isEqualTo("validation.required");
        claim.put("art12", "HOME_1H");
        JsonNode withClaim = body(phone.post("/app/explanations/" + fromId + "/sign", claim));
        assertThat(withClaim.get("status").asString()).as(withClaim.toString()).isEqualTo("SIGNED");
        assertThat(withClaim.get("art12").asString()).isEqualTo("HOME_1H");
        assertThat(withClaim.get("art12Place").asString()).isEqualTo("Baza Poznań");
        assertThat(withClaim.get("art12BreakFrom").isNull()).as("only for 2 h").isTrue();
        assertThat(withClaim.get("printoutNote").asBoolean()).isTrue();
        String claimText = pdfText(office.get("/app/explanations/" + fromId + "/pdf").getResponse()
                .getContentAsByteArray());
        assertThat(claimText).contains("art. 12 akapit drugi").contains("Baza Poznań")
                .contains("wpisano ręcznie na wydruku").contains("centrum operacyjnego pracodawcy")
                .doesNotContain("PROJEKT");

        // The import: a new recent violation gets one, an old one or with the setting off none.
        DriverViolation recent = violation(firmaId, driverId, Instant.now().minus(Duration.ofDays(1)));
        DriverViolation old = violation(firmaId, driverId, Instant.now().minus(Duration.ofDays(40)));
        RequestContext.runAsFirma(firmaId, null, () -> {
            service.onNewViolation(recent);
            service.onNewViolation(old);
            return null;
        });
        long auto = RequestContext.runAsFirma(firmaId, null, () -> explanations.findAll().stream()
                .filter(x -> x.getSource().name().equals("AUTO")).count());
        assertThat(auto).isEqualTo(1);
        long autoId = RequestContext.runAsFirma(firmaId, null, () -> explanations.findAll().stream()
                .filter(x -> x.getSource().name().equals("AUTO")).findFirst().orElseThrow().getId());
        JsonNode legacy = body(phone.post("/app/explanations/" + autoId + "/sign",
                Map.of("reason", "SAFE_STOPPING_PLACE", "signature", signature)));
        assertThat(legacy.get("reason").asString()).as("a first version code, no text needed")
                .isEqualTo("NO_PARKING_SPACE");

        Map<String, Object> change = new HashMap<>();
        change.put("name", settings.get("name").asString());
        change.put("lang", "pl");
        change.put("timezone", "Europe/Warsaw");
        change.put("baseCurrency", "PLN");
        change.put("explanationsAuto", false);
        change.put("explanationsDays", 14);
        change.put("country", "de");
        JsonNode saved = body(office.put("/app/admin/firma", change));
        assertThat(saved.get("country").asString()).isEqualTo("DE");
        assertThat(saved.get("explanationLang").asString()).isEqualTo("de");
        change.put("country", "FR");
        assertThat(body(office.put("/app/admin/firma", change)).get("explanationLang").asString())
                .as("no French template: English").isEqualTo("en");
        change.put("country", "AT");
        assertThat(body(office.put("/app/admin/firma", change)).get("explanationLang").asString()).isEqualTo("de");
        assertThat(saved.get("explanationsAuto").asBoolean()).isFalse();
        assertThat(saved.get("explanationsDays").asInt()).isEqualTo(14);
        DriverViolation later = violation(firmaId, otherId, Instant.now().minus(Duration.ofHours(5)));
        RequestContext.runAsFirma(firmaId, null, () -> {
            service.onNewViolation(later);
            return null;
        });
        assertThat(body(office.get("/app/explanations?driverId=" + otherId)).get("total").asLong())
                .as("automatic explanations off").isZero();
        change.put("country", "XX1");
        assertThat(body(office.put("/app/admin/firma", change)).get("details").get("country").asString())
                .isEqualTo("validation.invalid_format");
        change.put("country", "PL");
        change.put("explanationsDays", 0);
        assertThat(body(office.put("/app/admin/firma", change)).get("details").get("explanationsDays").asString())
                .isEqualTo("validation.invalid_value");
        assertThat(body(office.get("/app/explanations?status=SIGNED")).get("total").asLong()).isEqualTo(3);
    }
}

package com.rutera.core.explain;

import com.rutera.common.domain.DriverExplanation;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The PDF of a driver's explanation, assembled as the research describes (research/driver-explanations/README.md):
 * header, data table, intro, 1 the infringement, 2 the reason, 3 Article 12 (the claim with its note on the printout,
 * or "not claimed"), 4 the declaration (or the dispute), the driver's signature, 5 the employer's notes to fill in.
 * Pages follow as needed. Font Rubik (SIL OFL, resources/fonts); characters it lacks print as "?". Unsigned: a
 * watermark with the template's draft word ({@code watermark.draft}).
 */
@Component
public class ExplanationPdf {

    private static final float W = PDRectangle.A4.getWidth();
    private static final float H = PDRectangle.A4.getHeight();
    private static final float M = 50;
    private static final float BOTTOM = 60;

    /**
     * One explanation, ready to print: the placeholder values of the template, the infringement's template code and
     * label, the reason, the Article 12 claim and whether the infringement allows one, the signature.
     */
    public record Doc(Map<String, String> values, String infringementCode, String infringementLabel,
                      DriverExplanation.Reason reason, DriverExplanation.Art12 art12, boolean art12Possible,
                      Boolean printoutNote, byte[] signaturePng, boolean signed) {
    }

    private static final class Ctx {
        final PDDocument doc;
        final PDType0Font regular;
        final PDType0Font medium;
        final Map<Integer, Boolean> glyphs = new HashMap<>();
        final String watermark;
        final String footer;
        PDPageContentStream cs;
        float y;

        Ctx(PDDocument doc, String watermark, String footer) throws IOException {
            this.doc = doc;
            this.regular = load(doc, "/fonts/Rubik-Regular.ttf");
            this.medium = load(doc, "/fonts/Rubik-Medium.ttf");
            this.watermark = watermark;
            this.footer = footer;
        }
    }

    public byte[] render(Doc d, ExplanationTemplates.Template t, boolean draft) {
        try (PDDocument doc = new PDDocument()) {
            Ctx c = new Ctx(doc, draft ? draftWord(t) : null, t.fill("number", d.values()));
            newPage(c);
            try {
                content(c, d, t);
            } finally {
                c.cs.close();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Explanation PDF failed", e);
        }
    }

    /** The watermark of an unsigned preview, in the template's language. */
    private static String draftWord(ExplanationTemplates.Template t) {
        String word = t.text("watermark.draft");
        return word.isEmpty() ? "DRAFT" : word;
    }

    private void content(Ctx c, Doc d, ExplanationTemplates.Template t) throws IOException {
        Map<String, String> v = d.values();
        float full = W - 2 * M;
        // 1. header
        text(c, c.medium, 10, M, c.y, v.get("firma.name"));
        textRight(c, c.regular, 9, c.y, t.fill("number", v));
        c.y += 12;
        para(c, c.regular, 8.5f, M, full * 0.6f, v.get("firma.address"));
        if (v.get("firma.taxId") != null && !v.get("firma.taxId").isBlank()) {
            para(c, c.regular, 8.5f, M, full * 0.6f, t.text("label.taxId") + ": " + v.get("firma.taxId"));
        }
        textRight(c, c.regular, 9, M + 12, t.fill("placeDate", v));
        c.y += 16;
        centered(c, c.medium, 15, t.text("title"));
        centered(c, c.regular, 8.5f, t.text("subtitle"));
        c.y += 10;
        // 2. data table
        float label = 165;
        row(c, label, t.text("label.employer"), v.get("firma.name"));
        row(c, label, t.text("label.driver"), v.get("driver.fullName"));
        row(c, label, t.text("label.driverCard"), v.get("driver.cardNumber"));
        row(c, label, t.text("label.vehicle"), v.get("vehicle.plate"));
        row(c, label, t.text("label.infringement"), d.infringementLabel());
        row(c, label, t.text("label.period"), v.get("inf.period"));
        String downloaded = v.get("source.downloadedAt");
        row(c, label, t.text("label.dataSource"),
                v.get("source.label") + (downloaded == null ? "" : ", " + downloaded));
        c.y += 10;
        // 3. intro
        para(c, c.regular, 10, M, full, t.fill("intro", v));
        c.y += 8;
        // 4. the infringement
        heading(c, t.text("heading.facts"));
        para(c, c.regular, 10, M, full, t.fill("infringement." + d.infringementCode(), v));
        c.y += 8;
        // 5. reason and circumstances
        heading(c, t.text("heading.reason"));
        if (d.reason() != null) {
            para(c, c.regular, 10, M, full, t.fill("reason." + d.reason().name() + ".text", v));
        } else {
            para(c, c.regular, 10, M, full, ExplanationTemplates.BLANK + ExplanationTemplates.BLANK);
        }
        String details = v.get("reason.details");
        if (details != null && !details.isBlank() && d.reason() != DriverExplanation.Reason.DATA_DISPUTE) {
            c.y += 4;
            para(c, c.medium, 9, M, full, t.text("label.details"));
            para(c, c.regular, 10, M, full, details);
        }
        c.y += 8;
        // 6. Article 12
        heading(c, t.text("heading.art12"));
        DriverExplanation.Art12 art12 = d.art12() == null ? DriverExplanation.Art12.NONE : d.art12();
        if (!d.art12Possible() || art12 == DriverExplanation.Art12.NONE) {
            para(c, c.regular, 10, M, full, t.fill("art12.notClaimed", v));
        } else {
            String claim = switch (art12) {
                case SAFETY -> "art12.claimSafety";
                case HOME_1H -> "art12.claimHome1h";
                default -> "art12.claimHome2h";
            };
            para(c, c.regular, 10, M, full, t.fill(claim, v));
            if (art12 != DriverExplanation.Art12.SAFETY) {
                para(c, c.regular, 10, M, full, t.fill("art12.compensation", v));
            }
            if (d.printoutNote() != null) {
                para(c, c.regular, 10, M, full, t.fill(d.printoutNote() ? "art12.noteMade" : "art12.noteNotMade", v));
            }
        }
        c.y += 8;
        // 7. declaration
        heading(c, t.text("heading.declaration"));
        if (d.reason() == DriverExplanation.Reason.DATA_DISPUTE) {
            para(c, c.regular, 10, M, full, t.fill("declaration.dispute", v));
        } else {
            para(c, c.regular, 10, M, full, t.fill("declaration.truth", v));
        }
        c.y += 10;
        // 8. the driver's signature
        ensure(c, 95);
        float half = full / 2;
        if (d.signaturePng() != null) {
            PDImageXObject image = PDImageXObject.createFromByteArray(c.doc, d.signaturePng(), "signature");
            float scale = Math.min((half - 10) / image.getWidth(), 56f / image.getHeight());
            float iw = image.getWidth() * scale;
            float ih = image.getHeight() * scale;
            c.cs.drawImage(image, M + half + (half - iw) / 2, H - c.y - ih, iw, ih);
        }
        c.y += 60;
        text(c, c.regular, 10, M, c.y - 4, t.fill("placeDate", v));
        line(c, M, c.y, M + half - 20, c.y);
        line(c, M + half + 10, c.y, W - M, c.y);
        c.y += 11;
        text(c, c.regular, 8, M + half + 10, c.y, t.text("signature.driver"));
        c.y += 12;
        if (d.signed()) {
            para(c, c.regular, 8, M + half + 10, half - 10, t.fill("signature.electronic", v));
        }
        c.y += 14;
        // 9. the employer's notes, to fill in by hand
        ensure(c, 200); // the employer's part stays on one page
        heading(c, t.text("heading.employer"));
        para(c, c.regular, 9.5f, M, full, t.fill("employer.received", v));
        for (String assess : List.of("employer.assessJustified", "employer.assessNotJustified",
                "employer.assessPending")) {
            para(c, c.regular, 9.5f, M, full, "[   ]  " + t.fill(assess, v));
        }
        para(c, c.regular, 9.5f, M, full, "[   ]  " + t.fill("employer.instructed", v));
        para(c, c.regular, 9.5f, M, full, t.fill("employer.actions", v));
        ensure(c, 50);
        c.y += 34;
        line(c, M + half + 10, c.y, W - M, c.y);
        c.y += 11;
        text(c, c.regular, 8, M + half + 10, c.y, t.text("signature.employer"));
    }

    // --- layout ---------------------------------------------------------------------------------------------

    private static void newPage(Ctx c) throws IOException {
        if (c.cs != null) {
            c.cs.close();
        }
        PDPage page = new PDPage(PDRectangle.A4);
        c.doc.addPage(page);
        c.cs = new PDPageContentStream(c.doc, page);
        if (c.watermark != null) {
            watermark(c, c.watermark);
        }
        if (c.footer != null) {
            text(c, c.regular, 7, M, H - 28, c.footer + "  ·  " + c.doc.getNumberOfPages());
        }
        c.y = M;
    }

    /** A new page when the next {@code space} points do not fit. */
    private static void ensure(Ctx c, float space) throws IOException {
        if (c.y + space > H - BOTTOM) {
            newPage(c);
        }
    }

    private static void heading(Ctx c, String text) throws IOException {
        ensure(c, 30);
        text(c, c.medium, 10.5f, M, c.y, text);
        c.y += 15;
    }

    private static void row(Ctx c, float label, String name, String value) throws IOException {
        List<String> lines = lines(c, c.medium, 9.5f, W - 2 * M - label, value == null || value.isBlank()
                ? ExplanationTemplates.BLANK : value);
        ensure(c, lines.size() * 12.5f + 6);
        text(c, c.regular, 8.5f, M, c.y, name);
        for (String l : lines) {
            text(c, c.medium, 9.5f, M + label, c.y, l);
            c.y += 12.5f;
        }
        line(c, M, c.y - 8, W - M, c.y - 8);
        c.y += 4;
    }

    /** Wrapped text at x with this width; moves y below it and breaks pages as needed. */
    private static void para(Ctx c, PDType0Font font, float size, float x, float width, String text)
            throws IOException {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String l : lines(c, font, size, width, text)) {
            ensure(c, size + 3);
            text(c, font, size, x, c.y, l);
            c.y += size + 3.2f;
        }
    }

    private static void centered(Ctx c, PDType0Font font, float size, String text) throws IOException {
        for (String l : lines(c, font, size, W - 2 * M, text)) {
            text(c, font, size, (W - width(c, font, size, l)) / 2, c.y, l);
            c.y += size + 3;
        }
    }

    private static void textRight(Ctx c, PDType0Font font, float size, float y, String text) throws IOException {
        text(c, font, size, W - M - width(c, font, size, text), y, text);
    }

    private static void text(Ctx c, PDType0Font font, float size, float x, float y, String text) throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }
        c.cs.beginText();
        c.cs.setFont(font, size);
        c.cs.newLineAtOffset(x, H - y);
        c.cs.showText(printable(c, font, text));
        c.cs.endText();
    }

    private static void line(Ctx c, float x1, float y1, float x2, float y2) throws IOException {
        c.cs.setLineWidth(0.4f);
        c.cs.moveTo(x1, H - y1);
        c.cs.lineTo(x2, H - y2);
        c.cs.stroke();
    }

    private static List<String> lines(Ctx c, PDType0Font font, float size, float width, String text)
            throws IOException {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String paragraph : text.split("\\r?\\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.strip().split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (width(c, font, size, candidate) <= width || line.isEmpty()) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    private static float width(Ctx c, PDType0Font font, float size, String text) throws IOException {
        return font.getStringWidth(printable(c, font, text)) / 1000 * size;
    }

    private static void watermark(Ctx c, String text) throws IOException {
        c.cs.saveGraphicsState();
        c.cs.setNonStrokingColor(0.88f, 0.88f, 0.88f);
        c.cs.beginText();
        c.cs.setFont(c.medium, 96);
        c.cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(40), 150, 260));
        c.cs.showText(printable(c, c.medium, text));
        c.cs.endText();
        c.cs.restoreGraphicsState();
        c.cs.setNonStrokingColor(0f, 0f, 0f);
    }

    /** The text with every character the font cannot show replaced by "?" (control characters by a space). */
    private static String printable(Ctx c, PDType0Font font, String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp)) {
                out.append(' ');
                return;
            }
            boolean ok = c.glyphs.computeIfAbsent(cp, k -> {
                try {
                    font.encode(new String(Character.toChars(k)));
                    return true;
                } catch (IOException | IllegalArgumentException e) {
                    return false;
                }
            });
            out.append(ok ? new String(Character.toChars(cp)) : "?");
        });
        return out.toString();
    }

    private static PDType0Font load(PDDocument doc, String resource) throws IOException {
        try (InputStream in = ExplanationPdf.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing font " + resource);
            }
            return PDType0Font.load(doc, in);
        }
    }
}

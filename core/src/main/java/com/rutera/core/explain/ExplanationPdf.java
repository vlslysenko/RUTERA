package com.rutera.core.explain;

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
 * The PDF of a driver's explanation: one A4 page from a {@link ExplanationTemplates.Template} (employer, driver, card,
 * vehicle, the infringement, the statement, the reason, the declaration, place and date, the signature image). Font
 * Rubik (SIL OFL, resources/fonts); characters it lacks print as "?". Unsigned: a watermark.
 */
@Component
public class ExplanationPdf {

    private static final float W = PDRectangle.A4.getWidth();
    private static final float H = PDRectangle.A4.getHeight();
    private static final float M = 56;

    /** What the page shows, already formatted. */
    public record Data(String firma, String firmaAddress, String driver, String card, String vehicle, String date,
                       String start, String end, String infringement, String values, String exceeded,
                       String reasonCode, String reasonText, String place, String signedDate, byte[] signaturePng) {
    }

    private static final class Ctx {
        final PDDocument doc;
        final PDType0Font regular;
        final PDType0Font medium;
        final Map<Integer, Boolean> glyphs = new HashMap<>();
        PDPageContentStream cs;

        Ctx(PDDocument doc) throws IOException {
            this.doc = doc;
            this.regular = load(doc, "/fonts/Rubik-Regular.ttf");
            this.medium = load(doc, "/fonts/Rubik-Medium.ttf");
        }
    }

    public byte[] render(Data d, ExplanationTemplates.Template t, boolean draft) {
        try (PDDocument doc = new PDDocument()) {
            Ctx c = new Ctx(doc);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                c.cs = cs;
                if (draft) {
                    watermark(c, t.text("draft"));
                }
                page(c, d, t);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Explanation PDF failed", e);
        }
    }

    private void page(Ctx c, Data d, ExplanationTemplates.Template t) throws IOException {
        float y = M;
        text(c, c.medium, 9, M, y, nz(d.firma()));
        y += 12;
        y = paragraph(c, c.regular, 8, M, y, W - 2 * M, nz(d.firmaAddress())) + 22;
        float titleWidth = width(c, c.medium, 15, t.text("title"));
        text(c, c.medium, 15, (W - titleWidth) / 2, y, t.text("title"));
        y += 16;
        y = centered(c, c.regular, 8.5f, y, t.text("subtitle")) + 18;

        float label = 150;
        y = row(c, y, label, t.text("employer"), nz(d.firma()));
        y = row(c, y, label, t.text("driver"), nz(d.driver()));
        y = row(c, y, label, t.text("card"), or(d.card(), t));
        y = row(c, y, label, t.text("vehicle"), or(d.vehicle(), t));
        y = row(c, y, label, t.text("period"), nz(d.date()) + "  " + nz(d.start()) + " – " + nz(d.end()));
        y = row(c, y, label, t.text("infringement"), or(d.infringement(), t));
        y = row(c, y, label, t.text("values"), or(d.values(), t));
        y = row(c, y, label, t.text("exceeded"), or(d.exceeded(), t));
        y += 14;

        Map<String, String> values = new HashMap<>();
        values.put("driver", nz(d.driver()));
        values.put("card", or(d.card(), t));
        values.put("vehicle", or(d.vehicle(), t));
        values.put("date", nz(d.date()));
        values.put("start", nz(d.start()));
        values.put("end", nz(d.end()));
        values.put("infringement", or(d.infringement(), t));
        values.put("exceeded", or(d.exceeded(), t));
        values.put("firma", nz(d.firma()));
        y = paragraph(c, c.regular, 10, M, y, W - 2 * M, t.fill("statement", values)) + 16;

        text(c, c.medium, 10, M, y, t.text("reason_title"));
        y += 14;
        String reason = d.reasonCode() == null ? "" : t.text("reason." + d.reasonCode());
        y = paragraph(c, c.regular, 10, M, y, W - 2 * M, reason.isEmpty() ? "………………………………………………………………" : reason) + 6;
        if (d.reasonText() != null && !d.reasonText().isBlank()) {
            text(c, c.medium, 9, M, y, t.text("reason_text"));
            y += 12;
            y = paragraph(c, c.regular, 10, M, y, W - 2 * M, d.reasonText()) + 6;
        }
        y += 10;
        y = paragraph(c, c.regular, 10, M, y, W - 2 * M, t.text("declaration")) + 34;

        float half = (W - 2 * M) / 2;
        String placeDate = String.join(", ", nonBlank(d.place(), d.signedDate()));
        text(c, c.regular, 10, M, y, placeDate);
        if (d.signaturePng() != null) {
            PDImageXObject image = PDImageXObject.createFromByteArray(c.doc, d.signaturePng(), "signature");
            float maxW = half - 10;
            float maxH = 60;
            float scale = Math.min(maxW / image.getWidth(), maxH / image.getHeight());
            float iw = image.getWidth() * scale;
            float ih = image.getHeight() * scale;
            c.cs.drawImage(image, M + half + (half - iw) / 2, H - y - 4, iw, ih);
        }
        y += 8;
        line(c, M, y, M + half - 20, y);
        line(c, M + half + 10, y, W - M, y);
        text(c, c.regular, 8, M, y + 10, t.text("place_date"));
        text(c, c.regular, 8, M + half + 10, y + 10, t.text("signature"));
        text(c, c.regular, 7, M, H - 30, t.text("footer"));
    }

    private float row(Ctx c, float y, float label, String name, String value) throws IOException {
        text(c, c.regular, 9, M, y, name);
        float end = paragraph(c, c.medium, 10, M + label, y, W - 2 * M - label, value);
        line(c, M, end - 6, W - M, end - 6);
        return end + 6;
    }

    /** Wrapped text from (x, y) on; the y below its last line. */
    private float paragraph(Ctx c, PDType0Font font, float size, float x, float y, float width, String text)
            throws IOException {
        List<String> lines = lines(c, font, size, width, text);
        for (String line : lines) {
            text(c, font, size, x, y, line);
            y += size + 3;
        }
        return y;
    }

    private float centered(Ctx c, PDType0Font font, float size, float y, String text) throws IOException {
        for (String line : lines(c, font, size, W - 2 * M, text)) {
            text(c, font, size, (W - width(c, font, size, line)) / 2, y, line);
            y += size + 3;
        }
        return y;
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
        c.cs.setNonStrokingColor(0.86f, 0.86f, 0.86f);
        c.cs.beginText();
        c.cs.setFont(c.medium, 96);
        c.cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(40), 150, 260));
        c.cs.showText(printable(c, c.medium, text));
        c.cs.endText();
        c.cs.restoreGraphicsState();
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

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String or(String s, ExplanationTemplates.Template t) {
        return s == null || s.isBlank() ? t.text("none") : s;
    }

    private static List<String> nonBlank(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }
}

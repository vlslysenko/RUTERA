package com.rutera.core.trip;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Draws the CMR consignment note on A4 with Apache PDFBox: the 24 boxes of the international form with English,
 * Polish and German captions, one page per copy (1 sender, 2 consignee, 3 carrier, 4 extra). Font Rubik (SIL OFL,
 * resources/fonts); characters it lacks print as "?". A draft gets a "DRAFT" watermark.
 */
@Component
public class CmrPdf {

    private static final float W = PDRectangle.A4.getWidth();
    private static final float H = PDRectangle.A4.getHeight();
    private static final float M = 22;
    private static final float MID = W / 2;
    private static final float CAPTION = 5.6f;
    private static final float TEXT = 8.2f;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String[] COPIES = {
            "1  Copy for sender / Egzemplarz dla nadawcy / Exemplar für den Absender",
            "2  Copy for consignee / Egzemplarz dla odbiorcy / Exemplar für den Empfänger",
            "3  Copy for carrier / Egzemplarz dla przewoźnika / Exemplar für den Frachtführer",
            "4  Copy / Egzemplarz / Exemplar"};

    /** One rendering: the document, its fonts and the code points they can show. */
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

    /** The PDF of the note: {@code copies} pages (1-4). */
    public byte[] render(CmrDto.Cmr cmr, int copies, boolean draft) {
        try (PDDocument doc = new PDDocument()) {
            Ctx ctx = new Ctx(doc);
            for (int copy = 0; copy < Math.max(1, Math.min(4, copies)); copy++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    ctx.cs = cs;
                    page(ctx, cmr, copy, draft);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("CMR PDF failed", e);
        }
    }

    private void page(Ctx c, CmrDto.Cmr cmr, int copy, boolean draft) throws IOException {
        c.cs.setLineWidth(0.6f);
        float top = 24;
        // header (right half): title, number, copy
        text(c, c.medium, 9.5f, MID + 8, top + 12, "INTERNATIONAL CONSIGNMENT NOTE  CMR");
        text(c, c.regular, 7f, MID + 8, top + 22, "Międzynarodowy list przewozowy / Internationaler Frachtbrief");
        text(c, c.medium, 11f, MID + 8, top + 38, "No. " + nz(cmr.number()));
        text(c, c.regular, 6.5f, M, top + 10, COPIES[copy]);

        float y = top + 46;
        float leftH = 64;
        party(c, M, y, MID - M, leftH, "1", "Sender (name, address, country) / Nadawca / Absender", cmr.sender());
        String carrier = partyText(cmr.carrier());
        if (cmr.vehiclePlates() != null) {
            carrier = carrier + (carrier.isEmpty() ? "" : "\n") + "Vehicles / Pojazdy: " + cmr.vehiclePlates();
        }
        box(c, MID, y, W - M - MID, leftH, "16", "Carrier (name, address, country) / Przewoźnik / Frachtführer",
                carrier);
        y += leftH;
        party(c, M, y, MID - M, leftH, "2", "Consignee (name, address, country) / Odbiorca / Empfänger",
                cmr.consignee());
        List<String> successive = new ArrayList<>();
        if (cmr.successiveCarriers() != null) {
            for (CmrDto.Party p : cmr.successiveCarriers()) {
                successive.add(String.join(", ", nonBlank(p.name(), oneLine(p.address()), p.country())));
            }
        }
        box(c, MID, y, W - M - MID, leftH, "17", "Successive carriers / Kolejni przewoźnicy / Nachfolgende Frachtführer",
                String.join("\n", successive));
        y += leftH;
        float small = 40;
        box(c, M, y, MID - M, small, "3",
                "Place of delivery of the goods / Miejsce przeznaczenia / Auslieferungsort des Gutes",
                join(cmr.deliveryPlace(), cmr.deliveryCountry()));
        box(c, MID, y, W - M - MID, small * 3, "18",
                "Carrier's reservations and observations / Zastrzeżenia i uwagi przewoźnika / Vorbehalte und "
                        + "Bemerkungen der Frachtführer", cmr.carrierReservations());
        y += small;
        box(c, M, y, MID - M, small, "4",
                "Place and date of taking over the goods / Miejsce i data załadowania / Ort und Tag der Übernahme",
                join(cmr.loadingPlace(), cmr.loadingCountry()) + (cmr.loadingDate() == null ? ""
                        : "\n" + DATE.format(cmr.loadingDate())));
        y += small;
        box(c, M, y, MID - M, small, "5", "Documents attached / Załączone dokumenty / Beigefügte Dokumente",
                cmr.documentsAttached());
        y += small;

        y = goods(c, cmr, y);

        float lower = 50;
        box(c, M, y, MID - M, lower, "13", "Sender's instructions / Instrukcje nadawcy / Anweisungen des Absenders",
                cmr.senderInstructions());
        box(c, MID, y, W - M - MID, lower, "19", "Special agreements / Postanowienia specjalne / Besondere "
                + "Vereinbarungen", cmr.specialAgreements());
        y += lower;
        String terms = cmr.paymentTerms() == null ? "" : switch (cmr.paymentTerms()) {
            case PAID -> "Carriage paid / Franco / Frei";
            case FORWARD -> "Carriage forward / Nie franco / Unfrei";
        };
        box(c, M, y, MID - M, 36, "14", "Prescriptions as to payment of carriage / Postanowienia odnośnie "
                + "przewoźnego / Frachtzahlungsanweisungen", join(terms, cmr.paymentInstructions()));
        box(c, MID, y, W - M - MID, 36, "20", "To be paid by / Do zapłacenia / Zu zahlen vom", "");
        y += 36;
        CmrDto.CashOnDelivery cod = cmr.cashOnDelivery();
        box(c, M, y, MID - M, 26, "15", "Cash on delivery / Zapłata za zaliczeniem / Rückerstattung",
                cod == null || cod.amount() == null ? "" : cod.amount().toPlainString() + " " + nz(cod.currency()));
        box(c, MID, y, W - M - MID, 26, "21", "Established in / Wystawiono w / Ausgefertigt in",
                join(cmr.establishedPlace(), cmr.establishedDate() == null ? null
                        : DATE.format(cmr.establishedDate())));
        y += 26;

        float sign = 96;
        float third = (W - 2 * M) / 3;
        CmrDto.Signatures s = cmr.signatures();
        box(c, M, y, third, sign, "22", "Signature and stamp of the sender / Podpis i stempel nadawcy / Unterschrift "
                + "und Stempel des Absenders", signature(s == null ? null : s.sender()));
        box(c, M + third, y, third, sign, "23", "Signature and stamp of the carrier / Podpis i stempel przewoźnika / "
                + "Unterschrift und Stempel des Frachtführers", signature(s == null ? null : s.carrier()));
        box(c, M + 2 * third, y, third, sign, "24", "Goods received / Przesyłkę otrzymano / Gut empfangen",
                signature(s == null ? null : s.consignee()));
        y += sign;

        wrap(c, c.regular, 6f, M, y + 9, W - 2 * M, 3, "This carriage is subject, notwithstanding any clause to the "
                + "contrary, to the Convention on the Contract for the International Carriage of Goods by Road (CMR). "
                + "/ Niniejszy przewóz podlega, bez względu na jakąkolwiek przeciwną klauzulę, postanowieniom Konwencji "
                + "o umowie międzynarodowego przewozu drogowego towarów (CMR).");
        if (draft) {
            watermark(c, "DRAFT");
        }
    }

    /** Boxes 6-12 as a table; returns the y below it. */
    private float goods(Ctx c, CmrDto.Cmr cmr, float y) throws IOException {
        float height = 236;
        float width = W - 2 * M;
        float[] cols = {0.14f, 0.10f, 0.13f, 0.33f, 0.10f, 0.11f, 0.09f};
        String[][] heads = {
                {"6", "Marks and Nos", "Cechy i numery"}, {"7", "Number of packages", "Ilość sztuk"},
                {"8", "Method of packing", "Sposób opakowania"}, {"9", "Nature of the goods", "Rodzaj towaru"},
                {"10", "Statistical number", "Numer statystyczny"}, {"11", "Gross weight kg", "Waga brutto kg"},
                {"12", "Volume m3", "Objętość m3"}};
        rect(c, M, y, width, height);
        float x = M;
        float headH = 26;
        for (int i = 0; i < cols.length; i++) {
            float w = cols[i] * width;
            if (i > 0) {
                line(c, x, y, x, y + height);
            }
            int used = wrap(c, c.medium, CAPTION + 0.4f, x + 2, y + 7, w - 4, 2, heads[i][0] + "  " + heads[i][1]);
            wrap(c, c.regular, CAPTION, x + 2, y + 7 + used * (CAPTION + 2f), w - 4, 1, heads[i][2]);
            x += w;
        }
        line(c, M, y + headH, M + width, y + headH);
        float row = y + headH + 10;
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal volume = BigDecimal.ZERO;
        List<CmrDto.Line> lines = cmr.lines() == null ? List.of() : cmr.lines();
        for (CmrDto.Line l : lines) {
            if (row > y + height - 22) {
                break;
            }
            String[] cells = {l.marks(), l.packages() == null ? null : String.valueOf(l.packages()), l.packing(),
                    l.nature(), l.statNumber(), plain(l.grossWeightKg()), plain(l.volumeM3())};
            int used = 1;
            x = M;
            for (int i = 0; i < cols.length; i++) {
                float w = cols[i] * width;
                used = Math.max(used, wrap(c, c.regular, TEXT, x + 2, row, w - 4, 3, cells[i]));
                x += w;
            }
            row += used * (TEXT + 2) + 3;
            weight = l.grossWeightKg() == null ? weight : weight.add(l.grossWeightKg());
            volume = l.volumeM3() == null ? volume : volume.add(l.volumeM3());
        }
        if (lines.size() > 1) {
            float totalsX = M + (cols[0] + cols[1] + cols[2] + cols[3] + cols[4]) * width;
            line(c, totalsX, y + height - 14, M + width, y + height - 14);
            text(c, c.medium, TEXT, totalsX + 2, y + height - 4, plain(weight));
            text(c, c.medium, TEXT, totalsX + cols[5] * width + 2, y + height - 4, plain(volume));
        }
        return y + height;
    }

    // --- boxes ---------------------------------------------------------------------------------------------

    private void party(Ctx c, float x, float y, float w, float h, String no, String caption, CmrDto.Party p)
            throws IOException {
        box(c, x, y, w, h, no, caption, partyText(p));
    }

    /** Name, address lines, then country and VAT id on one line. */
    private static String partyText(CmrDto.Party p) {
        if (p == null) {
            return "";
        }
        return String.join("\n", nonBlank(p.name(), p.address(),
                join(p.country(), p.vatId() == null ? null : "VAT: " + p.vatId())));
    }

    private void box(Ctx c, float x, float y, float w, float h, String no, String caption, String body)
            throws IOException {
        rect(c, x, y, w, h);
        text(c, c.medium, CAPTION + 1, x + 3, y + 7, no);
        wrap(c, c.regular, CAPTION, x + 14, y + 7, w - 17, 2, caption);
        int captionLines = lines(c, c.regular, CAPTION, w - 17, caption).size() > 1 ? 2 : 1;
        float start = y + 7 + captionLines * (CAPTION + 1) + TEXT;
        int room = (int) ((y + h - 3 - start) / (TEXT + 1.6f)) + 1;
        wrap(c, c.regular, TEXT, x + 4, start, w - 8, Math.max(1, room), body);
    }

    private static String signature(CmrDto.Signature s) {
        if (s == null) {
            return "";
        }
        return String.join("\n", nonBlank(s.name(), join(s.place(), s.date() == null ? null : DATE.format(s.date()))));
    }

    // --- drawing primitives (y measured from the top of the page) ------------------------------------------

    private static void rect(Ctx c, float x, float y, float w, float h) throws IOException {
        c.cs.addRect(x, H - y - h, w, h);
        c.cs.stroke();
    }

    private static void line(Ctx c, float x1, float y1, float x2, float y2) throws IOException {
        c.cs.moveTo(x1, H - y1);
        c.cs.lineTo(x2, H - y2);
        c.cs.stroke();
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

    /** Wrapped text from (x, y) on; at most {@code maxLines} lines, the last one cut with "…". Returns lines used. */
    private static int wrap(Ctx c, PDType0Font font, float size, float x, float y, float width, int maxLines,
                            String text) throws IOException {
        if (text == null || text.isBlank()) {
            return 1;
        }
        List<String> lines = lines(c, font, size, width, text);
        int n = Math.min(maxLines, lines.size());
        for (int i = 0; i < n; i++) {
            String line = lines.get(i);
            if (i == n - 1 && lines.size() > n) {
                line = line + " …";
            }
            text(c, font, size, x, y + i * (size + 1.6f), line);
        }
        return n;
    }

    private static List<String> lines(Ctx c, PDType0Font font, float size, float width, String text)
            throws IOException {
        List<String> out = new ArrayList<>();
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
        c.cs.setNonStrokingColor(0.82f, 0.82f, 0.82f);
        c.cs.beginText();
        c.cs.setFont(c.medium, 110);
        c.cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(40), 130, 250));
        c.cs.showText(text);
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

    // --- helpers -------------------------------------------------------------------------------------------

    private static PDType0Font load(PDDocument doc, String resource) throws IOException {
        try (InputStream in = CmrPdf.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing font " + resource);
            }
            return PDType0Font.load(doc, in);
        }
    }

    private static List<String> nonBlank(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v.strip());
            }
        }
        return out;
    }

    private static String join(String a, String b) {
        return String.join(", ", nonBlank(a, b));
    }

    private static String oneLine(String s) {
        return s == null ? null : s.replaceAll("\\s*\\r?\\n\\s*", ", ");
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static String nz(String s) {
        return Objects.requireNonNullElse(s, "");
    }
}

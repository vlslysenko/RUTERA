package com.rutera.core.fuel.read;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the rows of a provider export: CSV (separator {@code ;}, {@code ,} or tab detected from the first lines,
 * RFC 4180 quotes; UTF-8 with or without BOM, UTF-16 with BOM, else Windows-1250) or XLSX (first sheet, read with the
 * JDK's zip and StAX, no Apache POI). Cells come back as trimmed strings; an XLSX number formatted as a date or time
 * becomes {@code yyyy-MM-dd HH:mm:ss}, other numbers their plain decimal form. Old binary {@code .xls} files are
 * rejected. Which row is the header is decided by the caller.
 */
public final class TableReader {

    /** The file cannot be read as a table (not CSV/XLSX, broken, too big). */
    public static final class UnreadableException extends Exception {
        public UnreadableException(String message) {
            super(message);
        }
    }

    private static final long MAX_XML_BYTES = 200L * 1024 * 1024;
    private static final LocalDateTime EXCEL_EPOCH = LocalDateTime.of(1899, 12, 30, 0, 0);

    private TableReader() {
    }

    /** All rows of the file (at most {@code maxRows}); empty rows are dropped. */
    public static List<List<String>> read(String fileName, byte[] data, int maxRows) throws UnreadableException {
        if (data.length >= 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4) {
            return xlsx(data, maxRows);
        }
        if (data.length >= 8 && (data[0] & 0xFF) == 0xD0 && (data[1] & 0xFF) == 0xCF && (data[2] & 0xFF) == 0x11
                && (data[3] & 0xFF) == 0xE0) {
            throw new UnreadableException("binary .xls is not supported, save the file as XLSX or CSV");
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".xls") || name.endsWith(".xlsx") || name.endsWith(".pdf")) {
            throw new UnreadableException("not a CSV or XLSX file");
        }
        return csv(decode(data), maxRows);
    }

    // --- CSV -----------------------------------------------------------------------------------------------

    static String decode(byte[] data) throws UnreadableException {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        }
        for (int i = 0; i < Math.min(data.length, 4096); i++) {
            if (data[i] == 0) {
                throw new UnreadableException("binary file");
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return new String(data, Charset.forName("windows-1250"));
        }
    }

    static List<List<String>> csv(String text, int maxRows) throws UnreadableException {
        char separator = separator(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"' && cell.toString().isBlank()) {
                cell.setLength(0);
                quoted = true;
            } else if (ch == separator) {
                row.add(cell.toString().strip());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString().strip());
                cell.setLength(0);
                addRow(rows, row, maxRows);
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString().strip());
            addRow(rows, row, maxRows);
        }
        return rows;
    }

    private static void addRow(List<List<String>> rows, List<String> row, int maxRows) throws UnreadableException {
        if (row.stream().allMatch(String::isEmpty)) {
            return;
        }
        if (rows.size() >= maxRows) {
            throw new UnreadableException("more than " + maxRows + " rows");
        }
        rows.add(row);
    }

    /** The separator found most often (outside quotes) in the first five lines. */
    static char separator(String text) {
        char[] candidates = {';', ',', '\t'};
        int[] counts = new int[candidates.length];
        int lines = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length() && lines < 5; i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (!quoted && ch == '\n') {
                lines++;
            } else if (!quoted) {
                for (int c = 0; c < candidates.length; c++) {
                    if (ch == candidates[c]) {
                        counts[c]++;
                    }
                }
            }
        }
        int best = 0;
        for (int c = 1; c < candidates.length; c++) {
            if (counts[c] > counts[best]) {
                best = c;
            }
        }
        return candidates[best];
    }

    // --- XLSX ----------------------------------------------------------------------------------------------

    private static List<List<String>> xlsx(byte[] data, int maxRows) throws UnreadableException {
        Map<String, byte[]> parts = unzip(data);
        if (!parts.containsKey("xl/workbook.xml")) {
            throw new UnreadableException("not an XLSX workbook");
        }
        try {
            List<String> shared = sharedStrings(parts.get("xl/sharedStrings.xml"));
            boolean[] dateStyles = dateStyles(parts.get("xl/styles.xml"));
            byte[] sheet = parts.get(firstSheet(parts));
            if (sheet == null) {
                throw new UnreadableException("the workbook has no sheet");
            }
            return sheet(sheet, shared, dateStyles, maxRows);
        } catch (XMLStreamException e) {
            throw new UnreadableException("broken XLSX: " + e.getMessage());
        }
    }

    private static Map<String, byte[]> unzip(byte[] data) throws UnreadableException {
        Map<String, byte[]> parts = new HashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                boolean wanted = name.equals("xl/workbook.xml") || name.equals("xl/_rels/workbook.xml.rels")
                        || name.equals("xl/sharedStrings.xml") || name.equals("xl/styles.xml")
                        || (name.startsWith("xl/worksheets/") && name.endsWith(".xml"));
                if (!wanted) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_XML_BYTES) {
                        throw new UnreadableException("the workbook is too large");
                    }
                    out.write(buffer, 0, read);
                }
                parts.put(name, out.toByteArray());
            }
        } catch (IOException e) {
            throw new UnreadableException("broken XLSX: " + e.getMessage());
        }
        return parts;
    }

    private static XMLStreamReader xml(byte[] part) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        InputStream in = new ByteArrayInputStream(part);
        return factory.createXMLStreamReader(in);
    }

    /** The path of the first sheet of the workbook (by its relationship), else sheet1. */
    private static String firstSheet(Map<String, byte[]> parts) throws XMLStreamException {
        String relId = null;
        XMLStreamReader r = xml(parts.get("xl/workbook.xml"));
        while (r.hasNext() && relId == null) {
            if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")) {
                for (int i = 0; i < r.getAttributeCount(); i++) {
                    if (r.getAttributeLocalName(i).equals("id")) {
                        relId = r.getAttributeValue(i);
                    }
                }
            }
        }
        byte[] rels = parts.get("xl/_rels/workbook.xml.rels");
        if (relId != null && rels != null) {
            r = xml(rels);
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("Relationship")
                        && relId.equals(r.getAttributeValue(null, "Id"))) {
                    String target = r.getAttributeValue(null, "Target");
                    return target.startsWith("/") ? target.substring(1) : "xl/" + target;
                }
            }
        }
        return "xl/worksheets/sheet1.xml";
    }

    private static List<String> sharedStrings(byte[] part) throws XMLStreamException {
        List<String> result = new ArrayList<>();
        if (part == null) {
            return result;
        }
        XMLStreamReader r = xml(part);
        StringBuilder text = null;
        boolean inT = false;
        boolean inPhonetic = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "si" -> text = new StringBuilder();
                    case "t" -> inT = !inPhonetic;
                    case "rPh" -> inPhonetic = true;
                    default -> {
                    }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                switch (r.getLocalName()) {
                    case "si" -> result.add(text == null ? "" : text.toString());
                    case "t" -> inT = false;
                    case "rPh" -> inPhonetic = false;
                    default -> {
                    }
                }
            } else if ((ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) && inT && text != null) {
                text.append(r.getText());
            }
        }
        return result;
    }

    /** Per cell style index: whether its number format shows a date or time. */
    private static boolean[] dateStyles(byte[] part) throws XMLStreamException {
        if (part == null) {
            return new boolean[0];
        }
        Map<Integer, Boolean> customDate = new HashMap<>();
        List<Boolean> styles = new ArrayList<>();
        XMLStreamReader r = xml(part);
        boolean inCellXfs = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "numFmt" -> customDate.put(Integer.parseInt(r.getAttributeValue(null, "numFmtId")),
                            isDateFormat(r.getAttributeValue(null, "formatCode")));
                    case "cellXfs" -> inCellXfs = true;
                    case "xf" -> {
                        if (inCellXfs) {
                            String id = r.getAttributeValue(null, "numFmtId");
                            int fmt = id == null ? 0 : Integer.parseInt(id);
                            boolean builtIn = (fmt >= 14 && fmt <= 22) || (fmt >= 45 && fmt <= 47);
                            styles.add(builtIn || customDate.getOrDefault(fmt, false));
                        }
                    }
                    default -> {
                    }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("cellXfs")) {
                inCellXfs = false;
            }
        }
        boolean[] result = new boolean[styles.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = styles.get(i);
        }
        return result;
    }

    /** A format code with d, m, y, h or s outside quotes and brackets (colours, locales) is a date or time. */
    static boolean isDateFormat(String code) {
        if (code == null) {
            return false;
        }
        boolean quoted = false;
        boolean bracket = false;
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (!quoted && ch == '[') {
                bracket = true;
            } else if (!quoted && ch == ']') {
                bracket = false;
            } else if (!quoted && !bracket && ch == '\\') {
                i++;
            } else if (!quoted && !bracket && "dmyhsDMYHS".indexOf(ch) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static List<List<String>> sheet(byte[] part, List<String> shared, boolean[] dateStyles, int maxRows)
            throws XMLStreamException, UnreadableException {
        List<List<String>> rows = new ArrayList<>();
        XMLStreamReader r = xml(part);
        List<String> row = null;
        int column = 0;
        String type = null;
        int style = 0;
        StringBuilder value = null;
        boolean inValue = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> row = new ArrayList<>();
                    case "c" -> {
                        String ref = r.getAttributeValue(null, "r");
                        column = ref == null ? (row == null ? 0 : row.size()) : columnIndex(ref);
                        type = r.getAttributeValue(null, "t");
                        String s = r.getAttributeValue(null, "s");
                        style = s == null ? 0 : Integer.parseInt(s);
                        value = new StringBuilder();
                    }
                    case "v", "t" -> inValue = value != null;
                    default -> {
                    }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                switch (r.getLocalName()) {
                    case "v", "t" -> inValue = false;
                    case "c" -> {
                        if (row != null && value != null) {
                            while (row.size() < column) {
                                row.add("");
                            }
                            row.add(cell(value.toString(), type, style, shared, dateStyles));
                        }
                        value = null;
                    }
                    case "row" -> {
                        if (row != null) {
                            addRow(rows, row, maxRows);
                        }
                        row = null;
                    }
                    default -> {
                    }
                }
            } else if ((ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) && inValue) {
                value.append(r.getText());
            }
        }
        return rows;
    }

    private static String cell(String raw, String type, int style, List<String> shared, boolean[] dateStyles) {
        String v = raw.strip();
        if (v.isEmpty()) {
            return "";
        }
        if ("s".equals(type)) {
            int index = Integer.parseInt(v);
            return index >= 0 && index < shared.size() ? shared.get(index).strip() : "";
        }
        if ("inlineStr".equals(type) || "str".equals(type) || "e".equals(type)) {
            return v;
        }
        if ("b".equals(type)) {
            return "1".equals(v) ? "TRUE" : "FALSE";
        }
        try {
            double number = Double.parseDouble(v);
            if (style >= 0 && style < dateStyles.length && dateStyles[style]) {
                return excelDate(number);
            }
            return new BigDecimal(v).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return v;
        }
    }

    /** An Excel serial date (1900 system) as {@code yyyy-MM-dd HH:mm:ss}. */
    static String excelDate(double serial) {
        long seconds = Math.round(serial * 86400);
        LocalDateTime at = EXCEL_EPOCH.plus(seconds, ChronoUnit.SECONDS);
        return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d", at.getYear(), at.getMonthValue(),
                at.getDayOfMonth(), at.getHour(), at.getMinute(), at.getSecond());
    }

    /** "C5" -> 2, "AA10" -> 26. */
    static int columnIndex(String ref) {
        int index = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch < 'A' || ch > 'Z') {
                break;
            }
            index = index * 26 + (ch - 'A' + 1);
        }
        return Math.max(0, index - 1);
    }
}

package com.rutera.core.fuel.read;

import com.rutera.common.domain.FuelProvider;
import com.rutera.common.domain.FuelTransaction.Product;
import com.rutera.common.domain.FuelTransaction.Unit;
import com.rutera.core.fuel.read.ColumnMapper.Field;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Readers of fuel card exports: CSV and XLSX tables, amounts, dates, products and the column detection. */
class FuelReadTest {

    @Test
    void amountsWithEitherDecimalSeparator() {
        assertThat(FuelValues.decimal("1 234,56", null)).isEqualByComparingTo("1234.56");
        assertThat(FuelValues.decimal("1.234,56", null)).isEqualByComparingTo("1234.56");
        assertThat(FuelValues.decimal("1,234.56", null)).isEqualByComparingTo("1234.56");
        assertThat(FuelValues.decimal("12,5", null)).isEqualByComparingTo("12.5");
        assertThat(FuelValues.decimal("12.5", null)).isEqualByComparingTo("12.5");
        assertThat(FuelValues.decimal("1.234.567", null)).isEqualByComparingTo("1234567");
        assertThat(FuelValues.decimal("-3,10", null)).isEqualByComparingTo("-3.10");
        assertThat(FuelValues.decimal("45,00 €", null)).isEqualByComparingTo("45.00");
        assertThat(FuelValues.decimal("1'250.00", null)).isEqualByComparingTo("1250.00");
        assertThat(FuelValues.decimal("1.234", true)).isEqualByComparingTo("1234");
        assertThat(FuelValues.decimal("1,234", false)).isEqualByComparingTo("1234");
        assertThat(FuelValues.decimal("  ", null)).isNull();
        assertThatThrownBy(() -> FuelValues.decimal("n/a", null)).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void datesWithTimeInTheSameOrAnotherCell() {
        assertThat(FuelValues.dateTime("31.10.2026 14:05", null, null))
                .isEqualTo(LocalDateTime.of(2026, 10, 31, 14, 5));
        assertThat(FuelValues.dateTime("2026-10-31", "14:05:30", null))
                .isEqualTo(LocalDateTime.of(2026, 10, 31, 14, 5, 30));
        assertThat(FuelValues.dateTime("31/10/26", "1899-12-30 07:15:00", null))
                .isEqualTo(LocalDateTime.of(2026, 10, 31, 7, 15));
        assertThat(FuelValues.dateTime("2026-10-31T06:00:00", null, null))
                .isEqualTo(LocalDateTime.of(2026, 10, 31, 6, 0));
        assertThat(FuelValues.dateTime("10/31/2026 14:05", null, "MM/dd/yyyy HH:mm"))
                .isEqualTo(LocalDateTime.of(2026, 10, 31, 14, 5));
        assertThatThrownBy(() -> FuelValues.dateTime("31.13.2026", null, null))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }

    @Test
    void productsUnitsCountriesAndCards() {
        assertThat(FuelValues.product("Diesel")).isEqualTo(Product.DIESEL);
        assertThat(FuelValues.product("ON Verva")).isEqualTo(Product.DIESEL);
        assertThat(FuelValues.product("Olej napędowy")).isEqualTo(Product.DIESEL);
        assertThat(FuelValues.product("Super Diesel")).isEqualTo(Product.DIESEL);
        assertThat(FuelValues.product("AdBlue")).isEqualTo(Product.ADBLUE);
        assertThat(FuelValues.product("PB95")).isEqualTo(Product.PETROL);
        assertThat(FuelValues.product("Maut DE")).isEqualTo(Product.TOLL);
        assertThat(FuelValues.product("Truck wash")).isEqualTo(Product.SERVICE);
        assertThat(FuelValues.product("Snacks")).isEqualTo(Product.OTHER);
        assertThat(FuelValues.unit("Ltr")).isEqualTo(Unit.L);
        assertThat(FuelValues.unit("szt.")).isEqualTo(Unit.PCS);
        assertThat(FuelValues.country("DEU")).isEqualTo("DE");
        assertThat(FuelValues.country("pl")).isEqualTo("PL");
        assertThat(FuelValues.currency("zł")).isEqualTo("PLN");
        assertThat(FuelValues.currency("eur")).isEqualTo("EUR");
        assertThat(FuelValues.cardNumber("7034 0512 3456 7890")).isEqualTo("7034051234567890");
        assertThat(FuelValues.maskCard("7034051234567890")).isEqualTo("7034 **** 7890");
        assertThat(FuelValues.cardNumber("7034 05** **** 7890")).isEqualTo("703405******7890");
    }

    @Test
    void csvWithQuotesSeparatorsAndWindows1250() throws Exception {
        String text = "Title line\r\n\"Product\";\"Amount\";Note\r\n\"Diesel; B7\";\"1 234,56\";\"say \"\"hi\"\"\"\r\n\r\n";
        List<List<String>> rows = TableReader.read("x.csv", text.getBytes(StandardCharsets.UTF_8), 100);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(2)).containsExactly("Diesel; B7", "1 234,56", "say \"hi\"");

        byte[] cp1250 = "Produkt,Kwota\nOlej napędowy,\"100,00\"\n".getBytes(Charset.forName("windows-1250"));
        List<List<String>> pl = TableReader.read("pl.csv", cp1250, 100);
        assertThat(pl.get(1)).containsExactly("Olej napędowy", "100,00");

        byte[] utf16 = ("﻿a\tb\tc\n1\t2\t3\n").getBytes(StandardCharsets.UTF_16LE);
        assertThat(TableReader.read("u.txt", utf16, 100).get(1)).containsExactly("1", "2", "3");

        assertThatThrownBy(() -> TableReader.read("big.csv", "a;b\n1;2\n3;4\n".getBytes(), 2))
                .isInstanceOf(TableReader.UnreadableException.class);
        byte[] xls = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        assertThatThrownBy(() -> TableReader.read("old.xls", xls, 100))
                .isInstanceOf(TableReader.UnreadableException.class);
    }

    @Test
    void xlsxWithSharedStringsAndDateCells() throws Exception {
        List<List<String>> rows = TableReader.read("export.xlsx", xlsx(), 100);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("Date", "Product", "Gross", "Card");
        assertThat(rows.get(1)).containsExactly("2026-10-31 14:30:00", "Diesel", "123.4", "", "inline");
    }

    @Test
    void headerRowAndColumnsAreFoundByName() {
        List<List<String>> rows = List.of(
                List.of("DKV transaction report", "", ""),
                List.of("Card number", "Licence plate", "Date", "Time", "Country", "Product", "Quantity",
                        "VAT %", "Net amount", "VAT amount", "Gross amount", "Currency", "Mileage"),
                List.of("7034...", "WX1234A", "31.10.2026", "14:05", "DE", "Diesel", "100,5", "19", "100", "19",
                        "119", "EUR", "250000"));
        assertThat(ColumnMapper.detectProvider("export.csv", rows)).isEqualTo(FuelProvider.DKV);
        ColumnMapper.Detection d = ColumnMapper.detect(rows, FuelProvider.DKV, null);
        assertThat(d.headerRow()).isEqualTo(1);
        assertThat(d.columns()).containsEntry(Field.CARD_NUMBER, 0).containsEntry(Field.PLATE, 1)
                .containsEntry(Field.DATE, 2).containsEntry(Field.TIME, 3).containsEntry(Field.QUANTITY, 6)
                .containsEntry(Field.NET, 8).containsEntry(Field.VAT, 9).containsEntry(Field.GROSS, 10)
                .containsEntry(Field.CURRENCY, 11).containsEntry(Field.ODOMETER, 12);

        List<List<String>> german = List.of(List.of("Kartennummer", "Kennzeichen", "Lieferdatum", "Warenart",
                "Menge", "MwSt-Satz", "Bruttobetrag", "Währung"));
        Map<Field, Integer> de = ColumnMapper.detect(german, FuelProvider.OTHER, null).columns();
        assertThat(de).containsEntry(Field.DATE, 2).containsEntry(Field.PRODUCT, 3).containsEntry(Field.GROSS, 6)
                .containsEntry(Field.CURRENCY, 7).doesNotContainKey(Field.VAT);

        List<List<String>> polish = List.of(List.of("Numer karty", "Nr rejestracyjny", "Data transakcji",
                "Nazwa produktu", "Ilość", "Kwota brutto", "Waluta", "Stan licznika"));
        Map<Field, Integer> pl = ColumnMapper.detect(polish, FuelProvider.ORLEN, null).columns();
        assertThat(pl).containsEntry(Field.CARD_NUMBER, 0).containsEntry(Field.PLATE, 1).containsEntry(Field.DATE, 2)
                .containsEntry(Field.PRODUCT, 3).containsEntry(Field.QUANTITY, 4).containsEntry(Field.GROSS, 5)
                .containsEntry(Field.ODOMETER, 7);

        Map<Field, Integer> explicit = ColumnMapper.detect(polish, FuelProvider.ORLEN,
                Map.of("odometer", "", "station", "Stan licznika")).columns();
        assertThat(explicit).containsEntry(Field.STATION, 7).doesNotContainKey(Field.ODOMETER);
    }

    /** A minimal workbook: shared strings, a date style (numFmt 22) and an inline string. */
    private static byte[] xlsx() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"
                    + "<sheet name=\"Tx\" sheetId=\"1\" r:id=\"rId7\"/></sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/"
                    + "2006/relationships\"><Relationship Id=\"rId7\" Type=\"worksheet\" Target=\"worksheets/data.xml\"/>"
                    + "</Relationships>");
            put(zip, "xl/sharedStrings.xml", "<sst><si><t>Date</t></si><si><t>Product</t></si>"
                    + "<si><r><t>Gro</t></r><r><t>ss</t></r></si><si><t>Card</t></si><si><t>Diesel</t></si></sst>");
            put(zip, "xl/styles.xml", "<styleSheet><cellXfs count=\"2\"><xf numFmtId=\"0\"/><xf numFmtId=\"22\"/>"
                    + "</cellXfs></styleSheet>");
            put(zip, "xl/worksheets/data.xml", "<worksheet><sheetData>"
                    + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c>"
                    + "<c r=\"C1\" t=\"s\"><v>2</v></c><c r=\"D1\" t=\"s\"><v>3</v></c></row>"
                    + "<row r=\"2\"><c r=\"A2\" s=\"1\"><v>46326.604166666664</v></c><c r=\"B2\" t=\"s\"><v>4</v></c>"
                    + "<c r=\"C2\"><v>123.40</v></c><c r=\"E2\" t=\"inlineStr\"><is><t>inline</t></is></c></row>"
                    + "</sheetData></worksheet>");
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String xml) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}

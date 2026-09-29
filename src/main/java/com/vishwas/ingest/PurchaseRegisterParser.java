package com.vishwas.ingest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses a purchase register exported as CSV (one row per invoice). Required columns:
 * supplier_gstin, supplier_name, invoice_no, invoice_date, taxable_value, igst, cgst, sgst.
 * Optional: voucher_no, booking_date, place_of_supply, hsn, description, gst_rate.
 * Quoted fields may contain commas. Column order does not matter.
 */
public class PurchaseRegisterParser {

    static final List<String> REQUIRED = List.of("supplier_gstin", "supplier_name", "invoice_no", "invoice_date",
            "taxable_value", "igst", "cgst", "sgst");

    public ParseResult parse(byte[] bytes, String period, String companyStateCode) {
        String text = new String(bytes, StandardCharsets.UTF_8).replace("﻿", "");
        List<List<String>> rows = csv(text);
        if (rows.isEmpty()) {
            throw new InvalidFileException("The purchase register is empty.");
        }
        Map<String, Integer> col = new HashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            col.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        List<String> missing = REQUIRED.stream().filter(c -> !col.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw new InvalidFileException("The purchase register is missing columns: " + String.join(", ", missing));
        }

        List<ParsedLine> lines = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            int lineNo = r + 1;
            try {
                String gstin = Gstin.clean(get(row, col, "supplier_gstin"));
                if (!Gstin.isValid(gstin)) {
                    warnings.add("Line " + lineNo + ": GSTIN " + gstin + " fails format/check-digit validation.");
                }
                InvoiceRow inv = new InvoiceRow(0, InvoiceRow.Source.BOOKS, InvoiceRow.Kind.INVOICE, period, gstin,
                        get(row, col, "supplier_name").trim(), get(row, col, "invoice_no").trim(),
                        Values.date(get(row, col, "invoice_date")), Values.money(get(row, col, "taxable_value")),
                        Values.money(get(row, col, "igst")), Values.money(get(row, col, "cgst")),
                        Values.money(get(row, col, "sgst")), null, null);
                if (inv.invoiceNo().isEmpty()) {
                    warnings.add("Line " + lineNo + ": skipped, invoice number is empty.");
                    continue;
                }
                checkTaxHeads(inv, companyStateCode, lineNo, warnings);
                String rate = get(row, col, "gst_rate");
                lines.add(new ParsedLine(inv, rate.isBlank() ? null : new BigDecimal(rate.trim()),
                        get(row, col, "hsn"), get(row, col, "description"), get(row, col, "place_of_supply"),
                        get(row, col, "voucher_no"), Values.date(get(row, col, "booking_date")), null, null));
            } catch (RuntimeException e) {
                warnings.add("Line " + lineNo + ": skipped, " + e.getMessage() + ".");
            }
        }
        return new ParseResult(lines, warnings);
    }

    /** IGST for an intra-state supplier (or CGST+SGST for an inter-state one) is worth a warning at import. */
    static void checkTaxHeads(InvoiceRow inv, String companyStateCode, int lineNo, List<String> warnings) {
        boolean sameState = Gstin.stateCode(inv.gstin()).equals(companyStateCode);
        if (inv.itc().signum() == 0) {
            return;
        }
        if (sameState && !inv.intraState()) {
            warnings.add("Line " + lineNo + ": " + inv.invoiceNo() + " is from a same-state supplier but carries IGST.");
        } else if (!sameState && inv.intraState()) {
            warnings.add("Line " + lineNo + ": " + inv.invoiceNo() + " is from an other-state supplier but carries CGST+SGST.");
        }
    }

    private static String get(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= row.size() ? "" : row.get(i);
    }

    /** Minimal RFC 4180 reader: quoted fields, escaped quotes, CRLF or LF line endings. */
    static List<List<String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}

package com.vishwas.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Vishwas's SIMPLIFIED GSTR-2B JSON (documented in docs/gstr2b-simplified.md). It is deliberately not
 * the GST portal schema: invoice-level values only, ISO dates, and three sections: b2b (invoices), b2ba
 * (amendments of earlier invoices) and cdnr (credit/debit notes).
 */
public class Gstr2bParser {

    public static final String FORMAT = "vishwas-gstr2b-simplified/1";

    private final ObjectMapper json = new ObjectMapper();

    public ParseResult parse(byte[] bytes, String expectedPeriod, String companyGstin) {
        JsonNode root;
        try {
            root = json.readTree(bytes);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new InvalidFileException("The GSTR-2B file is not valid JSON: " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new InvalidFileException("The GSTR-2B file could not be read: " + e.getMessage());
        }
        if (root == null || !root.isObject() || !FORMAT.equals(root.path("format").asText())) {
            throw new InvalidFileException("Expected a simplified GSTR-2B file with \"format\": \"" + FORMAT + "\".");
        }
        String period = root.path("return_period").asText();
        if (expectedPeriod != null && !expectedPeriod.equals(period)) {
            throw new InvalidFileException("This GSTR-2B is for " + period + ", not " + expectedPeriod + ".");
        }
        List<String> warnings = new ArrayList<>();
        String recipient = root.path("recipient_gstin").asText();
        if (companyGstin != null && !companyGstin.equals(recipient)) {
            warnings.add("Recipient GSTIN " + recipient + " is not this company's GSTIN " + companyGstin + ".");
        }

        List<ParsedLine> lines = new ArrayList<>();
        for (JsonNode supplier : root.path("b2b")) {
            for (JsonNode inv : supplier.path("invoices")) {
                lines.add(line(period, supplier, inv, InvoiceRow.Kind.INVOICE, inv.path("invoice_no").asText(), null, null, warnings));
            }
        }
        for (JsonNode supplier : root.path("b2ba")) {
            for (JsonNode inv : supplier.path("invoices")) {
                lines.add(line(period, supplier, inv, InvoiceRow.Kind.AMENDMENT, inv.path("invoice_no").asText(),
                        inv.path("original_invoice_no").asText(null), inv.path("original_invoice_date").asText(null), warnings));
            }
        }
        for (JsonNode supplier : root.path("cdnr")) {
            for (JsonNode note : supplier.path("notes")) {
                if (!"C".equalsIgnoreCase(note.path("note_type").asText("C"))) {
                    warnings.add("Debit note " + note.path("note_no").asText() + " ignored (only credit notes are reconciled).");
                    continue;
                }
                lines.add(line(period, supplier, note, InvoiceRow.Kind.CREDIT_NOTE, note.path("note_no").asText(),
                        note.path("original_invoice_no").asText(null), null, warnings));
            }
        }
        return new ParseResult(lines, warnings);
    }

    private static ParsedLine line(String period, JsonNode supplier, JsonNode inv, InvoiceRow.Kind kind, String number,
                                   String originalNo, String originalDate, List<String> warnings) {
        String gstin = Gstin.clean(supplier.path("supplier_gstin").asText());
        if (!Gstin.isValid(gstin)) {
            warnings.add("Supplier GSTIN " + gstin + " fails format/check-digit validation.");
        }
        String dateField = kind == InvoiceRow.Kind.CREDIT_NOTE ? "note_date" : "invoice_date";
        InvoiceRow row = new InvoiceRow(0, InvoiceRow.Source.GSTR2B, kind, period, gstin,
                supplier.path("supplier_name").asText(), number, Values.date(inv.path(dateField).asText(null)),
                Values.money(inv.path("taxable_value")), Values.money(inv.path("igst")), Values.money(inv.path("cgst")),
                Values.money(inv.path("sgst")), originalNo, Values.date(originalDate));
        JsonNode rate = inv.path("rate");
        return new ParsedLine(row, rate.isNumber() ? rate.decimalValue() : (rate.isTextual() ? new BigDecimal(rate.asText()) : null),
                null, null, inv.path("place_of_supply").asText(null), null, null,
                Values.date(supplier.path("gstr1_filed_on").asText(null)), supplier.path("gstr1_period").asText(null));
    }
}

package com.vishwas.support;

import com.vishwas.ingest.InvoiceRow;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Terse builders for invoice rows in tests. Amounts are strings to keep test tables readable. */
public final class Rows {

    public static final String BALAJI_TRADERS = "36ABKFS2231Q1ZP";
    public static final String BALAJI_ENTERPRISES = "36ADSFS7710L1ZD";
    public static final String KAVERI = "29AAHCK5512D1ZO";
    public static final String METRO = "36AAPFM8841E1ZX";

    private Rows() {
    }

    /** Intra-state books row: CGST and SGST are each half of {@code tax}. */
    public static InvoiceRow booksIntra(long id, String gstin, String no, String date, String taxable, String tax) {
        BigDecimal half = new BigDecimal(tax).divide(BigDecimal.TWO);
        return row(id, InvoiceRow.Source.BOOKS, InvoiceRow.Kind.INVOICE, gstin, no, date, taxable, "0", half.toPlainString(),
                half.toPlainString(), null);
    }

    public static InvoiceRow booksInter(long id, String gstin, String no, String date, String taxable, String igst) {
        return row(id, InvoiceRow.Source.BOOKS, InvoiceRow.Kind.INVOICE, gstin, no, date, taxable, igst, "0", "0", null);
    }

    public static InvoiceRow g2bIntra(long id, String gstin, String no, String date, String taxable, String tax) {
        BigDecimal half = new BigDecimal(tax).divide(BigDecimal.TWO);
        return row(id, InvoiceRow.Source.GSTR2B, InvoiceRow.Kind.INVOICE, gstin, no, date, taxable, "0", half.toPlainString(),
                half.toPlainString(), null);
    }

    public static InvoiceRow g2bInter(long id, String gstin, String no, String date, String taxable, String igst) {
        return row(id, InvoiceRow.Source.GSTR2B, InvoiceRow.Kind.INVOICE, gstin, no, date, taxable, igst, "0", "0", null);
    }

    public static InvoiceRow amendmentIntra(long id, String gstin, String no, String original, String date, String taxable, String tax) {
        BigDecimal half = new BigDecimal(tax).divide(BigDecimal.TWO);
        return row(id, InvoiceRow.Source.GSTR2B, InvoiceRow.Kind.AMENDMENT, gstin, no, date, taxable, "0", half.toPlainString(),
                half.toPlainString(), original);
    }

    public static InvoiceRow creditNoteInter(long id, String gstin, String noteNo, String original, String date, String taxable, String igst) {
        return row(id, InvoiceRow.Source.GSTR2B, InvoiceRow.Kind.CREDIT_NOTE, gstin, noteNo, date, taxable, igst, "0", "0", original);
    }

    public static InvoiceRow row(long id, InvoiceRow.Source source, InvoiceRow.Kind kind, String gstin, String no, String date,
                                 String taxable, String igst, String cgst, String sgst, String original) {
        return new InvoiceRow(id, source, kind, date.substring(0, 7), gstin, "Vendor " + gstin.substring(2, 7), no,
                LocalDate.parse(date), new BigDecimal(taxable).setScale(2), new BigDecimal(igst).setScale(2),
                new BigDecimal(cgst).setScale(2), new BigDecimal(sgst).setScale(2), original, null);
    }
}

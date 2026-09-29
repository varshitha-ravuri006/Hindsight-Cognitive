package com.vishwas.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One invoice-level line from either side of the reconciliation, as a plain immutable value. The matcher and
 * outcome detector work only on these, never on entities, so they stay pure and easy to test.
 *
 * @param id                  database id of the stored row (0 for rows not yet stored)
 * @param source              BOOKS (purchase register) or GSTR2B
 * @param kind                INVOICE, AMENDMENT (GSTR-2B B2BA) or CREDIT_NOTE (GSTR-2B CDNR)
 * @param period              the return period the row belongs to (yyyy-MM)
 * @param invoiceNo           as written; for a credit note the note number
 * @param originalInvoiceNo   for amendments and credit notes: the invoice they refer to
 */
public record InvoiceRow(
        long id,
        Source source,
        Kind kind,
        String period,
        String gstin,
        String supplierName,
        String invoiceNo,
        LocalDate invoiceDate,
        BigDecimal taxableValue,
        BigDecimal igst,
        BigDecimal cgst,
        BigDecimal sgst,
        String originalInvoiceNo,
        LocalDate originalInvoiceDate) {

    public enum Source { BOOKS, GSTR2B }

    public enum Kind { INVOICE, AMENDMENT, CREDIT_NOTE }

    /** Total input tax credit on the row: IGST + CGST + SGST. */
    public BigDecimal itc() {
        return nz(igst).add(nz(cgst)).add(nz(sgst));
    }

    /** True when tax is split CGST+SGST (intra-state), false when IGST (inter-state). */
    public boolean intraState() {
        return nz(igst).signum() == 0 && (nz(cgst).signum() != 0 || nz(sgst).signum() != 0);
    }

    public String normalisedNo() {
        return InvoiceNumbers.normalise(invoiceNo);
    }

    public String normalisedOriginalNo() {
        return InvoiceNumbers.normalise(originalInvoiceNo);
    }

    public InvoiceRow withId(long newId) {
        return new InvoiceRow(newId, source, kind, period, gstin, supplierName, invoiceNo, invoiceDate, taxableValue,
                igst, cgst, sgst, originalInvoiceNo, originalInvoiceDate);
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

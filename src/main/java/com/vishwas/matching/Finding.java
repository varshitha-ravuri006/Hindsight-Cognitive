package com.vishwas.matching;

import com.vishwas.ingest.InvoiceRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One mismatch found by the matcher, before it is stored.
 *
 * @param books        the purchase-register row (null for MISSING_IN_BOOKS)
 * @param gstr2b       the GSTR-2B row (null for MISSING_IN_2B)
 * @param differences  field-by-field differences, original values side by side
 * @param candidates   fuzzy candidates for a MISSING_IN_2B row: shown, never silently matched
 * @param exposure     potential ITC exposure (never a loss): see {@link Matcher#exposure}
 * @param linkedRowId  for MISSING_IN_BOOKS: the books row this 2B row is a candidate for, if any
 */
public record Finding(
        MismatchType type,
        InvoiceRow books,
        InvoiceRow gstr2b,
        List<Difference> differences,
        List<Candidate> candidates,
        BigDecimal exposure,
        Long linkedRowId,
        String note) {

    /** One differing field with both original values. */
    public record Difference(String field, String books, String gstr2b) {
    }

    /**
     * A GSTR-2B row that might be the same invoice as a missing books row. Original and normalised numbers are
     * both kept so the UI can show them side by side.
     */
    public record Candidate(long rowId, String invoiceNo, String normalisedNo, LocalDate invoiceDate,
                            BigDecimal taxableValue, BigDecimal itc, long dateGapDays, BigDecimal amountDifference,
                            int editDistance, List<String> reasons) {
    }

    public InvoiceRow primary() {
        return books != null ? books : gstr2b;
    }
}

package com.vishwas.advisor;

import com.vishwas.matching.MismatchType;

/**
 * What a rule-based reconciliation tool says with no memory: the textbook action for each mismatch type,
 * identical for every vendor. This is Vishwas's fallback when Hindsight is unavailable, and the contrast
 * that shows what memory adds.
 */
public final class TextbookRules {

    private TextbookRules() {
    }

    public static String action(MismatchType type) {
        return switch (type) {
            case MISSING_IN_2B -> "Send the vendor a reminder to file GSTR-1 and do not claim this ITC until the invoice appears in GSTR-2B.";
            case MISSING_IN_BOOKS -> "Obtain the invoice from the vendor and book it, or confirm it does not belong to the company.";
            case AMOUNT_MISMATCH -> "Ask the vendor to amend the amounts in GSTR-1 or issue a credit/debit note; claim ITC only as per GSTR-2B.";
            case TAX_HEAD_MISMATCH -> "Ask the vendor to amend the tax head (IGST vs CGST+SGST) in GSTR-1.";
            case GSTIN_MISMATCH -> "Verify the supplier GSTIN on the invoice and in the vendor master; ask the vendor to correct GSTR-1.";
            case INVOICE_NO_FORMAT -> "Ask the vendor to correct the invoice number in GSTR-1 so it matches the invoice.";
            case DATE_MISMATCH -> "Ask the vendor to correct the invoice date in GSTR-1.";
            case POSSIBLE_DUPLICATE -> "Check whether the invoice was booked twice and reverse the duplicate entry.";
        };
    }

    /** The cause a textbook tool implicitly assumes for each type. */
    public static Cause assumedCause(MismatchType type) {
        return switch (type) {
            case MISSING_IN_2B -> Cause.VENDOR_NOT_FILING;
            case MISSING_IN_BOOKS -> Cause.TIMING_DIFFERENCE;
            case AMOUNT_MISMATCH, TAX_HEAD_MISMATCH, INVOICE_NO_FORMAT, DATE_MISMATCH -> Cause.AMENDMENT_EXPECTED;
            case GSTIN_MISMATCH -> Cause.WRONG_GSTIN;
            case POSSIBLE_DUPLICATE -> Cause.DUPLICATE_BOOKING;
        };
    }
}

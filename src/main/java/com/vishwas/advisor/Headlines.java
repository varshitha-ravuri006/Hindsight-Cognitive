package com.vishwas.advisor;

import com.vishwas.ingest.Fmt;
import com.vishwas.matching.Dimension;
import com.vishwas.matching.MismatchType;
import com.vishwas.matching.Outcome;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The one-line explanation beside each case, composed deterministically from the verified ledger and the
 * category and cause that memory chose. Memory decides and explains; it never authors the numbers, because a
 * trust product cannot show "8 of 8" when the ledger says 4 of 4.
 */
public final class Headlines {

    private Headlines() {
    }

    /**
     * @param brokenPromiseBy date of the vendor's most recent broken promise, if any
     */
    public static String compose(Category category, Cause cause, MismatchType type, Dimension dim, BigDecimal exposure,
                                 String invoice, HistoryStats stats, LocalDate brokenPromiseBy) {
        return compose(category, cause, type, dim, exposure, invoice, stats, brokenPromiseBy, null, null);
    }

    /**
     * @param candidate  invoice number of a fuzzy candidate in GSTR-2B, if any
     * @param monthsOpen for a case carried forward still unresolved, how many months it has been open
     */
    public static String compose(Category category, Cause cause, MismatchType type, Dimension dim, BigDecimal exposure,
                                 String invoice, HistoryStats stats, LocalDate brokenPromiseBy, String candidate,
                                 Integer monthsOpen) {
        String lead = monthsOpen == null ? "" : "Still unresolved after " + monthsOpen + (monthsOpen == 1 ? " month. " : " months. ");
        return lead + body(category, cause, type, dim, exposure, invoice, stats, brokenPromiseBy, candidate);
    }

    private static String body(Category category, Cause cause, MismatchType type, Dimension dim, BigDecimal exposure,
                               String invoice, HistoryStats stats, LocalDate brokenPromiseBy, String candidate) {
        int judged = stats.judgedCount();
        long unresolved = stats.cases().stream().filter(c -> c.status().open()).count();
        String promise = brokenPromiseBy == null ? "" : " and broke a written promise to file by " + Fmt.day(brokenPromiseBy);

        if (type == MismatchType.POSSIBLE_DUPLICATE) {
            return "Possible duplicate booking: invoice " + invoice + " appears twice in the purchase register (" + Fmt.inr(exposure)
                    + " ITC). Escalated: history cannot decide this.";
        }
        if (type == MismatchType.GSTIN_MISMATCH) {
            return "Conflicting records: the same invoice is reported under a different GSTIN of the supplier (" + Fmt.inr(exposure)
                    + " ITC). Escalated for review.";
        }
        if (type == MismatchType.MISSING_IN_BOOKS) {
            return "Not yet booked: this invoice is in GSTR-2B but not in the purchase register. No ITC is at risk; "
                    + "book it or confirm it is not ours.";
        }
        if (candidate != null && type == MismatchType.MISSING_IN_2B) {
            return "Possible data-entry difference in our books: GSTR-2B has " + candidate + " with the same date and amounts. "
                    + "Confirm before treating it as the same invoice.";
        }
        if (category == Category.AUTO_RESOLVE) {
            return "Format difference only: " + stats.count(Outcome.CONFIRMED_TYPO) + " of " + judged + " past format differences "
                    + "from this vendor were confirmed typos, and GSTIN, date and amounts match. Approved rule FORMAT_ONLY applies.";
        }
        if (category.needsReview() && unresolved > 0) {
            return "Potential payment risk: this vendor has " + unresolved + " past unresolved case" + (unresolved == 1 ? "" : "s")
                    + " (" + Fmt.inr(stats.openExposure()) + " exposure)" + promise + ".";
        }
        if (category.needsReview() && judged < 3) {
            String history = judged == 0 ? "no past cases" : "only " + judged + " past case" + (judged == 1 ? "" : "s");
            return "Needs review: this vendor has " + history + " on " + dim.label().toLowerCase() + ", so history cannot vouch for "
                    + "this invoice (" + risk(exposure) + ")" + promise + ".";
        }
        if (cause == Cause.TIMING_DIFFERENCE && judged > 0) {
            return "Possible timing difference: this vendor's missing invoices appeared in the next GSTR-2B in "
                    + stats.count(Outcome.RESOLVED_LATE) + " of " + judged + " past cases.";
        }
        if (judged > 0 && stats.dominantOutcome().isPresent()) {
            Outcome o = stats.dominantOutcome().get();
            String likely = switch (o) {
                case AMENDED -> "Vendor amendment likely";
                case CREDIT_NOTE -> "Credit note likely";
                case CONFIRMED_TYPO -> "Format difference likely";
                case RESOLVED_LATE -> "Possible timing difference";
                case DUPLICATE -> "Possible duplicate";
                case UNRESOLVED_AT_RISK -> "Potential payment risk";
            };
            return likely + ": " + stats.count(o) + " of " + judged + " past " + dim.label().toLowerCase()
                    + " cases from this vendor ended " + o.label().toLowerCase() + ".";
        }
        return cause.label() + ": no past cases on " + dim.label().toLowerCase() + " for this vendor (" + risk(exposure) + ").";
    }

    private static String risk(BigDecimal exposure) {
        return exposure == null || exposure.signum() == 0 ? "no ITC at risk" : Fmt.inr(exposure) + " exposure";
    }
}

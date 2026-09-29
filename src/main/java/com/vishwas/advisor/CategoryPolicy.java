package com.vishwas.advisor;

import com.vishwas.matching.MismatchType;
import com.vishwas.matching.Outcome;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic guardrails around every recommendation. Memory proposes; this policy decides what is allowed.
 * Rules, in order:
 * <ol>
 *   <li>POSSIBLE_DUPLICATE and GSTIN_MISMATCH always ESCALATE (suspicious pattern / conflicting records).</li>
 *   <li>Without memory, every other mismatch gets the textbook category REQUIRE_REVIEW.</li>
 *   <li>AUTO_RESOLVE only through an approved deterministic rule ({@code FORMAT_ONLY}: a pure invoice-number
 *       format difference with identical GSTIN, date and amounts) AND history that supports it (the proposal
 *       says so, or every past case on the dimension was a confirmed typo with enough cases). Otherwise an
 *       AUTO_RESOLVE proposal is downgraded to RECOMMEND.</li>
 *   <li>A next step that mentions a payment action lifts the case to at least REQUIRE_REVIEW.</li>
 *   <li>Material exposure (at or above the materiality threshold) with thin history cannot stay RECOMMEND.</li>
 * </ol>
 */
public class CategoryPolicy {

    public static final String FORMAT_ONLY = "FORMAT_ONLY";

    private static final Pattern PAYMENT_ACTION = Pattern.compile(
            "(?i)\\b(hold|withhold|stop|release|block|delay|defer|suspend)\\w*\\s+(the\\s+|all\\s+|any\\s+)?(vendor\\s+)?payments?\\b"
                    + "|\\bpayments?\\s+(hold|block|stop)");

    public record Settings(BigDecimal materiality, int thinHistoryCases, boolean formatOnlyApproved) {
    }

    /** What memory proposed for a case (null when memory is unavailable). */
    public record Proposal(Category category, Cause topCause, String nextStep) {
    }

    public record Input(MismatchType type, BigDecimal exposure, boolean formatOnlyEligible, Proposal proposal,
                        HistoryStats history) {
    }

    /**
     * @param guardrails human-readable notes on every rule that changed or constrained the category
     * @param rule       the approved deterministic rule behind an AUTO_RESOLVE, else null
     */
    public record Decision(Category category, List<String> guardrails, String rule) {
    }

    private final Settings settings;

    public CategoryPolicy(Settings settings) {
        this.settings = settings;
    }

    public Decision decide(Input in) {
        List<String> notes = new ArrayList<>();
        if (in.type() == MismatchType.POSSIBLE_DUPLICATE) {
            notes.add("Possible duplicate booking: always escalated, never decided from history.");
            return new Decision(Category.ESCALATE, notes, null);
        }
        if (in.type() == MismatchType.GSTIN_MISMATCH) {
            notes.add("Conflicting records (supplier GSTIN differs): always escalated.");
            return new Decision(Category.ESCALATE, notes, null);
        }
        if (in.proposal() == null || in.proposal().category() == null) {
            notes.add("No memory available: textbook action, reviewed by a person.");
            return new Decision(Category.REQUIRE_REVIEW, notes, null);
        }

        Category category = in.proposal().category();
        String rule = null;
        boolean historySupportsTypo = in.history().judgedCount() >= settings.thinHistoryCases()
                && in.history().count(Outcome.CONFIRMED_TYPO) == in.history().judgedCount();
        boolean ruleApplies = in.formatOnlyEligible() && settings.formatOnlyApproved();
        if (ruleApplies && (category == Category.AUTO_RESOLVE || historySupportsTypo)
                && category != Category.ESCALATE && category != Category.REQUIRE_REVIEW) {
            if (category != Category.AUTO_RESOLVE) {
                notes.add("Approved rule FORMAT_ONLY applies and every past format case was a confirmed typo.");
            }
            category = Category.AUTO_RESOLVE;
            rule = FORMAT_ONLY;
        } else if (category == Category.AUTO_RESOLVE) {
            notes.add(in.formatOnlyEligible() && !settings.formatOnlyApproved()
                    ? "Rule FORMAT_ONLY is not approved by the finance team: downgraded to Recommend."
                    : "Auto-resolve needs an approved deterministic rule: downgraded to Recommend.");
            category = Category.RECOMMEND;
        }

        if (in.proposal().nextStep() != null && PAYMENT_ACTION.matcher(in.proposal().nextStep()).find()
                && category.ordinal() < Category.REQUIRE_REVIEW.ordinal()) {
            notes.add("Payment actions are never recommended: lifted to Review.");
            category = Category.REQUIRE_REVIEW;
            rule = null;
        }

        boolean thin = in.history().judgedCount() < settings.thinHistoryCases();
        boolean material = in.exposure() != null && in.exposure().compareTo(settings.materiality()) >= 0;
        if (material && thin && category.ordinal() < Category.REQUIRE_REVIEW.ordinal()) {
            notes.add("Material exposure with thin history: lifted to Review.");
            category = Category.REQUIRE_REVIEW;
            rule = null;
        }
        return new Decision(category, notes, rule);
    }

    /** True when the text proposes a payment action (exposed for the baseline and e-mail drafts too). */
    public static boolean mentionsPaymentAction(String text) {
        return text != null && PAYMENT_ACTION.matcher(text).find();
    }

    /**
     * FORMAT_ONLY eligibility: an INVOICE_NO_FORMAT case whose GSTIN, date, taxable value and ITC are identical
     * (within tolerance) on both sides; only the way the number is written differs.
     */
    public static boolean formatOnlyEligible(MismatchType type, java.time.LocalDate booksDate, java.time.LocalDate g2bDate,
                                             BigDecimal booksTaxable, BigDecimal g2bTaxable, BigDecimal booksItc,
                                             BigDecimal g2bItc, BigDecimal tolerance) {
        return type == MismatchType.INVOICE_NO_FORMAT
                && booksDate != null && booksDate.equals(g2bDate)
                && close(booksTaxable, g2bTaxable, tolerance)
                && close(booksItc, g2bItc, tolerance);
    }

    private static boolean close(BigDecimal a, BigDecimal b, BigDecimal tol) {
        return a != null && b != null && a.subtract(b).abs().compareTo(tol) <= 0;
    }
}

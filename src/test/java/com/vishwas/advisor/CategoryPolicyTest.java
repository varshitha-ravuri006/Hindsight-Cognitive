package com.vishwas.advisor;

import com.vishwas.matching.MismatchStatus;
import com.vishwas.matching.MismatchType;
import com.vishwas.matching.Outcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryPolicyTest {

    private final CategoryPolicy policy = new CategoryPolicy(new CategoryPolicy.Settings(new BigDecimal("50000"), 3, true));

    static HistoryStats history(Outcome outcome, int n) {
        return new HistoryStats(IntStream.range(0, n).mapToObj(i -> new HistoryStats.PastCase(i, "2026-0" + (4 + i), "X" + i,
                "T", new BigDecimal("1000"), outcome == Outcome.UNRESOLVED_AT_RISK ? MismatchStatus.AT_RISK : MismatchStatus.RESOLVED,
                outcome, 1, 30L, new BigDecimal("1000"), BigDecimal.ZERO)).toList());
    }

    private static CategoryPolicy.Proposal proposal(Category c, Cause cause, String step) {
        return new CategoryPolicy.Proposal(c, cause, step);
    }

    @Test
    void duplicatesAlwaysEscalateEvenWhenMemorySaysOtherwise() {
        var d = policy.decide(new CategoryPolicy.Input(MismatchType.POSSIBLE_DUPLICATE, new BigDecimal("12960"), false,
                proposal(Category.RECOMMEND, Cause.TIMING_DIFFERENCE, "wait"), history(Outcome.RESOLVED_LATE, 5)));
        assertThat(d.category()).isEqualTo(Category.ESCALATE);
    }

    @Test
    void gstinConflictsEscalate() {
        var d = policy.decide(new CategoryPolicy.Input(MismatchType.GSTIN_MISMATCH, new BigDecimal("100"), false, null,
                HistoryStats.empty()));
        assertThat(d.category()).isEqualTo(Category.ESCALATE);
    }

    @Test
    void withoutMemoryEverythingElseIsTextbookReview() {
        for (MismatchType t : List.of(MismatchType.MISSING_IN_2B, MismatchType.AMOUNT_MISMATCH, MismatchType.INVOICE_NO_FORMAT)) {
            var d = policy.decide(new CategoryPolicy.Input(t, new BigDecimal("100"), true, null, history(Outcome.CONFIRMED_TYPO, 5)));
            assertThat(d.category()).as(t.name()).isEqualTo(Category.REQUIRE_REVIEW);
            assertThat(d.rule()).isNull();
        }
    }

    @Test
    void recommendationFromMemoryIsKeptForSmallConsistentCases() {
        var d = policy.decide(new CategoryPolicy.Input(MismatchType.MISSING_IN_2B, new BigDecimal("16740"), false,
                proposal(Category.RECOMMEND, Cause.TIMING_DIFFERENCE, "Check the next GSTR-2B before sending a reminder."),
                history(Outcome.RESOLVED_LATE, 4)));
        assertThat(d.category()).isEqualTo(Category.RECOMMEND);
        assertThat(d.guardrails()).isEmpty();
    }

    @Test
    void autoResolveNeedsTheApprovedFormatRule() {
        var eligible = policy.decide(new CategoryPolicy.Input(MismatchType.INVOICE_NO_FORMAT, BigDecimal.ZERO, true,
                proposal(Category.AUTO_RESOLVE, Cause.DATA_ENTRY_TYPO, "Accept"), history(Outcome.CONFIRMED_TYPO, 1)));
        assertThat(eligible.category()).isEqualTo(Category.AUTO_RESOLVE);
        assertThat(eligible.rule()).isEqualTo(CategoryPolicy.FORMAT_ONLY);

        var notEligible = policy.decide(new CategoryPolicy.Input(MismatchType.MISSING_IN_2B, new BigDecimal("900"), false,
                proposal(Category.AUTO_RESOLVE, Cause.TIMING_DIFFERENCE, "Accept"), history(Outcome.RESOLVED_LATE, 5)));
        assertThat(notEligible.category()).isEqualTo(Category.RECOMMEND);
        assertThat(notEligible.guardrails()).anyMatch(g -> g.contains("approved deterministic rule"));
    }

    @Test
    void unapprovedRuleNeverAutoResolves() {
        var strict = new CategoryPolicy(new CategoryPolicy.Settings(new BigDecimal("50000"), 3, false));
        var d = strict.decide(new CategoryPolicy.Input(MismatchType.INVOICE_NO_FORMAT, BigDecimal.ZERO, true,
                proposal(Category.AUTO_RESOLVE, Cause.DATA_ENTRY_TYPO, "Accept"), history(Outcome.CONFIRMED_TYPO, 4)));
        assertThat(d.category()).isEqualTo(Category.RECOMMEND);
        assertThat(d.guardrails()).anyMatch(g -> g.contains("not approved"));
    }

    @Test
    void consistentTypoHistoryPromotesAnEligibleFormatCaseToAutoResolve() {
        var d = policy.decide(new CategoryPolicy.Input(MismatchType.INVOICE_NO_FORMAT, BigDecimal.ZERO, true,
                proposal(Category.RECOMMEND, Cause.DATA_ENTRY_TYPO, "Accept the format difference"), history(Outcome.CONFIRMED_TYPO, 4)));
        assertThat(d.category()).isEqualTo(Category.AUTO_RESOLVE);
    }

    @Test
    void paymentActionsAreNeverRecommended() {
        var d = policy.decide(new CategoryPolicy.Input(MismatchType.MISSING_IN_2B, new BigDecimal("10800"), false,
                proposal(Category.RECOMMEND, Cause.VENDOR_NOT_FILING, "Hold the payment until they file."), history(Outcome.UNRESOLVED_AT_RISK, 3)));
        assertThat(d.category()).isEqualTo(Category.REQUIRE_REVIEW);
        assertThat(CategoryPolicy.mentionsPaymentAction("withhold vendor payments")).isTrue();
        assertThat(CategoryPolicy.mentionsPaymentAction("Review invoice, contract and payment status")).isFalse();
    }

    @Test
    void materialExposureWithThinHistoryIsLiftedToReview() {
        var d = policy.decide(new CategoryPolicy.Input(MismatchType.MISSING_IN_2B, new BigDecimal("75000"), false,
                proposal(Category.RECOMMEND, Cause.TIMING_DIFFERENCE, "Wait for the next refresh"), history(Outcome.RESOLVED_LATE, 1)));
        assertThat(d.category()).isEqualTo(Category.REQUIRE_REVIEW);

        var enoughHistory = policy.decide(new CategoryPolicy.Input(MismatchType.MISSING_IN_2B, new BigDecimal("75000"), false,
                proposal(Category.RECOMMEND, Cause.TIMING_DIFFERENCE, "Wait for the next refresh"), history(Outcome.RESOLVED_LATE, 4)));
        assertThat(enoughHistory.category()).isEqualTo(Category.RECOMMEND);
    }

    @Test
    void formatOnlyEligibilityRequiresIdenticalDateAndAmounts() {
        var d = LocalDate.of(2026, 8, 8);
        var a = new BigDecimal("310000");
        var t = new BigDecimal("55800");
        var tol = BigDecimal.ONE;
        assertThat(CategoryPolicy.formatOnlyEligible(MismatchType.INVOICE_NO_FORMAT, d, d, a, a, t, t, tol)).isTrue();
        assertThat(CategoryPolicy.formatOnlyEligible(MismatchType.INVOICE_NO_FORMAT, d, d.plusDays(1), a, a, t, t, tol)).isFalse();
        assertThat(CategoryPolicy.formatOnlyEligible(MismatchType.INVOICE_NO_FORMAT, d, d, a, a.add(BigDecimal.TEN), t, t, tol)).isFalse();
        assertThat(CategoryPolicy.formatOnlyEligible(MismatchType.DATE_MISMATCH, d, d, a, a, t, t, tol)).isFalse();
    }
}

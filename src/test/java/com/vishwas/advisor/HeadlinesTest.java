package com.vishwas.advisor;

import com.vishwas.matching.Dimension;
import com.vishwas.matching.MismatchStatus;
import com.vishwas.matching.MismatchType;
import com.vishwas.matching.Outcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeadlinesTest {

    static HistoryStats.PastCase c(String amount, MismatchStatus s, Outcome o) {
        return new HistoryStats.PastCase(1, "2026-05", "X", "MISSING_IN_2B", new BigDecimal(amount), s, o, 1, null,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void timingDifferenceUsesTheLedgerCountsInTheBriefsWording() {
        var stats = CategoryPolicyTest.history(Outcome.RESOLVED_LATE, 4);
        assertThat(Headlines.compose(Category.RECOMMEND, Cause.TIMING_DIFFERENCE, MismatchType.MISSING_IN_2B, Dimension.TIMING,
                new BigDecimal("16740"), "SBT/2026/0118", stats, null))
                .isEqualTo("Possible timing difference: this vendor's missing invoices appeared in the next GSTR-2B in 4 of 4 past cases.");
    }

    @Test
    void paymentRiskStatesUnresolvedCasesExposureAndTheBrokenPromise() {
        var stats = new HistoryStats(List.of(c("12600", MismatchStatus.AT_RISK, Outcome.UNRESOLVED_AT_RISK),
                c("13500", MismatchStatus.AT_RISK, Outcome.UNRESOLVED_AT_RISK), c("15900", MismatchStatus.OPEN, null),
                c("8640", MismatchStatus.WRITTEN_OFF, Outcome.UNRESOLVED_AT_RISK)));
        assertThat(Headlines.compose(Category.REQUIRE_REVIEW, Cause.VENDOR_NOT_FILING, MismatchType.MISSING_IN_2B, Dimension.TIMING,
                new BigDecimal("10800"), "KPL/0631", stats, LocalDate.of(2026, 7, 20)))
                .isEqualTo("Potential payment risk: this vendor has 3 past unresolved cases (Rs 42,000 exposure) and broke a written "
                        + "promise to file by 20 Jul 2026.");
    }

    @Test
    void thinHistoryIsSaidPlainly() {
        assertThat(Headlines.compose(Category.REQUIRE_REVIEW, Cause.TIMING_DIFFERENCE, MismatchType.MISSING_IN_2B, Dimension.TIMING,
                new BigDecimal("55800"), "SBE-2331", HistoryStats.empty(), null))
                .startsWith("Needs review: this vendor has no past cases on filing timing");
    }

    @Test
    void duplicatesAndAutoResolveHaveTheirOwnWording() {
        assertThat(Headlines.compose(Category.ESCALATE, Cause.DUPLICATE_BOOKING, MismatchType.POSSIBLE_DUPLICATE, Dimension.DUPLICATES,
                new BigDecimal("12960"), "CFP/3318", HistoryStats.empty(), null)).startsWith("Possible duplicate booking: invoice CFP/3318");
        assertThat(Headlines.compose(Category.AUTO_RESOLVE, Cause.DATA_ENTRY_TYPO, MismatchType.INVOICE_NO_FORMAT, Dimension.INVOICE_FORMAT,
                BigDecimal.ZERO, "142", CategoryPolicyTest.history(Outcome.CONFIRMED_TYPO, 4), null))
                .startsWith("Format difference only: 4 of 4 past format differences");
    }

    @Test
    void candidatesAndCarriedForwardCasesAreNamed() {
        assertThat(Headlines.compose(Category.RECOMMEND, Cause.DATA_ENTRY_TYPO, MismatchType.MISSING_IN_2B, Dimension.TIMING,
                new BigDecimal("38700"), "DCW/2026/1187", HistoryStats.empty(), null, "DCW/2026/1178", null))
                .startsWith("Possible data-entry difference in our books: GSTR-2B has DCW/2026/1178");
        assertThat(Headlines.compose(Category.REQUIRE_REVIEW, Cause.VENDOR_NOT_FILING, MismatchType.MISSING_IN_2B, Dimension.TIMING,
                new BigDecimal("12600"), "KPL/0502", CategoryPolicyTest.history(Outcome.UNRESOLVED_AT_RISK, 3), null, null, 3))
                .startsWith("Still unresolved after 3 months. Potential payment risk");
    }

    @Test
    void otherCausesUseTheDominantOutcome() {
        assertThat(Headlines.compose(Category.RECOMMEND, Cause.AMENDMENT_EXPECTED, MismatchType.TAX_HEAD_MISMATCH,
                Dimension.TAX_HEAD_CORRECTNESS, new BigDecimal("2000"), "MLS/1131", CategoryPolicyTest.history(Outcome.AMENDED, 2), null))
                .isEqualTo("Vendor amendment likely: 2 of 2 past tax head correctness cases from this vendor ended amended by vendor.");
    }
}

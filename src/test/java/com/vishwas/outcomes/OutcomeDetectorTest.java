package com.vishwas.outcomes;

import com.vishwas.matching.MismatchStatus;
import com.vishwas.matching.MismatchType;
import com.vishwas.matching.Outcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.vishwas.support.Rows.BALAJI_TRADERS;
import static com.vishwas.support.Rows.KAVERI;
import static com.vishwas.support.Rows.METRO;
import static com.vishwas.support.Rows.amendmentIntra;
import static com.vishwas.support.Rows.booksIntra;
import static com.vishwas.support.Rows.creditNoteInter;
import static com.vishwas.support.Rows.g2bInter;
import static com.vishwas.support.Rows.g2bIntra;
import static org.assertj.core.api.Assertions.assertThat;

class OutcomeDetectorTest {

    private final OutcomeDetector detector = new OutcomeDetector(new OutcomeDetector.Settings(new BigDecimal("1.00"), 2));

    private static OpenCase open(long id, MismatchType type, String period, String gstin, String norm, String exposure) {
        return new OpenCase(id, type, period, MismatchStatus.OPEN, gstin, norm, norm, new BigDecimal(exposure), new BigDecimal(exposure));
    }

    @Test
    void missingInvoiceAppearingNextMonthIsResolvedLateByOneMonth() {
        var c = open(1, MismatchType.MISSING_IN_2B, "2026-08", BALAJI_TRADERS, "20260118", "16740");
        var later = g2bIntra(501, BALAJI_TRADERS, "SBT/2026/0118", "2026-08-04", "93000", "16740");

        var j = detector.judge(List.of(c), List.of(later), List.of(), "2026-09");

        assertThat(j.verdicts()).singleElement().satisfies(v -> {
            assertThat(v.outcome()).isEqualTo(Outcome.RESOLVED_LATE);
            assertThat(v.monthsLate()).isEqualTo(1);
            assertThat(v.recovered()).isEqualByComparingTo("16740");
            assertThat(v.explanation()).contains("1 month late").contains("September 2026");
        });
        assertThat(j.consumedRowIds()).containsExactly(501L);
    }

    @Test
    void appearingWithDifferentAmountsIsAmended() {
        var c = open(1, MismatchType.MISSING_IN_2B, "2026-06", KAVERI, "547", "13500");
        var later = g2bInter(502, KAVERI, "KPL/0547", "2026-06-20", "70000", "12600");
        var v = detector.judge(List.of(c), List.of(later), List.of(), "2026-09").verdicts().get(0);
        assertThat(v.outcome()).isEqualTo(Outcome.AMENDED);
        assertThat(v.monthsLate()).isEqualTo(3);
    }

    @Test
    void creditNoteSettlesAnAmountMismatch() {
        var c = open(1, MismatchType.AMOUNT_MISMATCH, "2026-08", "29AAJCN6620H1Z8", "7851", "1620");
        var note = creditNoteInter(503, "29AAJCN6620H1Z8", "NEL/CN/044", "NEL/7851", "2026-09-12", "9000", "1620");
        var v = detector.judge(List.of(c), List.of(note), List.of(), "2026-09").verdicts().get(0);
        assertThat(v.outcome()).isEqualTo(Outcome.CREDIT_NOTE);
        assertThat(v.evidenceRowId()).isEqualTo(503);
    }

    @Test
    void amendmentFixesATaxHeadMismatch() {
        var c = open(1, MismatchType.TAX_HEAD_MISMATCH, "2026-08", METRO, "1131", "2000");
        var amendment = amendmentIntra(504, METRO, "MLS/1131", "MLS/1131", "2026-08-06", "40000", "2000");
        var v = detector.judge(List.of(c), List.of(amendment), List.of(), "2026-09").verdicts().get(0);
        assertThat(v.outcome()).isEqualTo(Outcome.AMENDED);
        assertThat(v.explanation()).contains("CGST+SGST");
    }

    @Test
    void formatDifferenceWithNoAmendmentIsAConfirmedTypo() {
        var c = open(1, MismatchType.INVOICE_NO_FORMAT, "2026-08", "37AACCG3309R1Z8", "142", "0");
        var v = detector.judge(List.of(c), List.of(), List.of(), "2026-09").verdicts().get(0);
        assertThat(v.outcome()).isEqualTo(Outcome.CONFIRMED_TYPO);
    }

    @Test
    void formatDifferenceWithAConflictingInvoiceStaysOpen() {
        var c = open(1, MismatchType.INVOICE_NO_FORMAT, "2026-08", "37AACCG3309R1Z8", "142", "0");
        var conflicting = g2bInter(505, "37AACCG3309R1Z8", "INV/26-27/0142", "2026-09-02", "5000", "900");
        assertThat(detector.judge(List.of(c), List.of(conflicting), List.of(), "2026-09").verdicts()).isEmpty();
    }

    @Test
    void duplicateIsConfirmedWhenTheVendorReportedOnlyOneInvoice() {
        var c = open(1, MismatchType.POSSIBLE_DUPLICATE, "2026-08", "36AAKFC1914B1ZY", "3318", "12960");
        var v = detector.judge(List.of(c), List.of(), List.of(), "2026-09").verdicts().get(0);
        assertThat(v.outcome()).isEqualTo(Outcome.DUPLICATE);
    }

    @Test
    void gstinMismatchResolvesWhenReportedUnderTheBookedGstin() {
        var c = open(1, MismatchType.GSTIN_MISMATCH, "2026-08", "29AADCT2256K1Z1", "5520", "21600");
        var reissued = g2bInter(506, "29AADCT2256K1Z1", "TMP/5520", "2026-08-18", "120000", "21600");
        var v = detector.judge(List.of(c), List.of(reissued), List.of(), "2026-09").verdicts().get(0);
        assertThat(v.outcome()).isEqualTo(Outcome.AMENDED);
    }

    @Test
    void missingInBooksResolvesWhenBookedLater() {
        var c = open(1, MismatchType.MISSING_IN_BOOKS, "2026-08", "36AAKFG7719P1ZM", "88", "0");
        var booked = booksIntra(507, "36AAKFG7719P1ZM", "GIS/26-27/088", "2026-08-30", "45000", "8100");
        var v = detector.judge(List.of(c), List.of(), List.of(booked), "2026-09").verdicts().get(0);
        assertThat(v.outcome()).isEqualTo(Outcome.RESOLVED_LATE);
    }

    @Test
    void stillMissingAfterTwoMonthsIsAtRiskButStaysOpen() {
        var oneMonth = open(1, MismatchType.MISSING_IN_2B, "2026-07", KAVERI, "589", "15900");
        var twoMonths = open(2, MismatchType.MISSING_IN_2B, "2026-06", KAVERI, "547", "13500");

        var j = detector.judge(List.of(oneMonth, twoMonths), List.of(), List.of(), "2026-08");

        assertThat(j.verdicts()).singleElement().satisfies(v -> {
            assertThat(v.caseId()).isEqualTo(2);
            assertThat(v.outcome()).isEqualTo(Outcome.UNRESOLVED_AT_RISK);
            assertThat(v.outcome().terminal()).isFalse();
            assertThat(v.recovered()).isEqualByComparingTo("0");
        });
    }

    @Test
    void atRiskIsNotRepeatedButAnAtRiskCaseCanStillResolveLate() {
        var atRisk = new OpenCase(2, MismatchType.MISSING_IN_2B, "2026-06", MismatchStatus.AT_RISK, KAVERI, "547", "KPL/0547",
                new BigDecimal("13500"), new BigDecimal("13500"));
        assertThat(detector.judge(List.of(atRisk), List.of(), List.of(), "2026-09").verdicts()).isEmpty();

        var filed = g2bInter(508, KAVERI, "KPL/0547", "2026-06-20", "75000", "13500");
        var v = detector.judge(List.of(atRisk), List.of(filed), List.of(), "2026-09").verdicts().get(0);
        assertThat(v.outcome()).isEqualTo(Outcome.RESOLVED_LATE);
        assertThat(v.monthsLate()).isEqualTo(3);
    }

    @Test
    void eachLaterRowResolvesOnlyOneCase() {
        var a = open(1, MismatchType.MISSING_IN_2B, "2026-07", BALAJI_TRADERS, "20260099", "900");
        var b = open(2, MismatchType.MISSING_IN_2B, "2026-08", BALAJI_TRADERS, "20260099", "900");
        var later = g2bIntra(509, BALAJI_TRADERS, "SBT/2026/0099", "2026-07-30", "5000", "900");
        var j = detector.judge(List.of(b, a), List.of(later), List.of(), "2026-09");
        assertThat(j.verdicts()).filteredOn(v -> v.outcome() == Outcome.RESOLVED_LATE)
                .singleElement().satisfies(v -> assertThat(v.caseId()).isEqualTo(1));
    }

    @Test
    void casesFromTheSameOrLaterPeriodAreNotJudged() {
        var sameMonth = open(1, MismatchType.MISSING_IN_2B, "2026-09", KAVERI, "700", "100");
        assertThat(detector.judge(List.of(sameMonth), List.of(), List.of(), "2026-09").verdicts()).isEmpty();
    }
}

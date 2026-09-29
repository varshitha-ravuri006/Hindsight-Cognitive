package com.vishwas.matching;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.vishwas.support.Rows.BALAJI_TRADERS;
import static com.vishwas.support.Rows.KAVERI;
import static com.vishwas.support.Rows.METRO;
import static com.vishwas.support.Rows.booksInter;
import static com.vishwas.support.Rows.booksIntra;
import static com.vishwas.support.Rows.g2bInter;
import static com.vishwas.support.Rows.g2bIntra;
import static org.assertj.core.api.Assertions.assertThat;

class MatcherTest {

    private final Matcher matcher = new Matcher(new Matcher.Settings(new BigDecimal("1.00"), 10, 2));

    private static final String GODAVARI = "37AACCG3309R1Z8";
    private static final String TUNGABHADRA_KA = "29AADCT2256K1Z1";
    private static final String TUNGABHADRA_TS = "36AADCT2256K2Z5";
    private static final String DECCAN_COPPER = "36AAECD7305N1ZF";

    @Test
    void identicalInvoicesMatchExactly() {
        var r = matcher.match(List.of(booksIntra(1, BALAJI_TRADERS, "SBT/2026/0101", "2026-08-02", "50000", "9000")),
                List.of(g2bIntra(101, BALAJI_TRADERS, "SBT/2026/0101", "2026-08-02", "50000", "9000")));
        assertThat(r.exactMatches()).isEqualTo(1);
        assertThat(r.findings()).isEmpty();
    }

    @Test
    void differencesWithinOneRupeeStillMatch() {
        var r = matcher.match(List.of(booksIntra(1, BALAJI_TRADERS, "SBT/1", "2026-08-02", "50000.40", "9000")),
                List.of(g2bIntra(101, BALAJI_TRADERS, "SBT/1", "2026-08-02", "50000.00", "9000.80")));
        assertThat(r.exactMatches()).isEqualTo(1);
    }

    @Test
    void missingIn2bCarriesFullItcAsExposure() {
        var r = matcher.match(List.of(booksInter(1, KAVERI, "KPL/0631", "2026-08-10", "60000", "10800")), List.of());
        var f = only(r, MismatchType.MISSING_IN_2B);
        assertThat(f.exposure()).isEqualByComparingTo("10800");
        assertThat(f.candidates()).isEmpty();
        assertThat(f.type().dimension()).isEqualTo(Dimension.TIMING);
    }

    @Test
    void missingInBooksHasNoExposure() {
        var r = matcher.match(List.of(), List.of(g2bIntra(101, "36AAKFG7719P1ZM", "GIS/26-27/088", "2026-08-30", "45000", "8100")));
        var f = only(r, MismatchType.MISSING_IN_BOOKS);
        assertThat(f.exposure()).isEqualByComparingTo("0");
        assertThat(f.gstr2b().invoiceNo()).isEqualTo("GIS/26-27/088");
    }

    @Test
    void amountMismatchExposureIsTheItcDifference() {
        var r = matcher.match(List.of(booksInter(1, "29AAJCN6620H1Z8", "NEL/7851", "2026-08-12", "150000", "27000")),
                List.of(g2bInter(101, "29AAJCN6620H1Z8", "NEL/7851", "2026-08-12", "159000", "28620")));
        var f = only(r, MismatchType.AMOUNT_MISMATCH);
        assertThat(f.exposure()).isEqualByComparingTo("1620");
        assertThat(f.differences()).extracting(Finding.Difference::field).contains("taxable value", "total tax (ITC)");
        assertThat(f.type().dimension()).isEqualTo(Dimension.AMOUNT_ACCURACY);
    }

    @Test
    void igstOnIntraStateSupplyIsTaxHeadMismatch() {
        var r = matcher.match(List.of(booksIntra(1, METRO, "MLS/1131", "2026-08-06", "40000", "2000")),
                List.of(g2bInter(101, METRO, "MLS/1131", "2026-08-06", "40000", "2000")));
        var f = only(r, MismatchType.TAX_HEAD_MISMATCH);
        assertThat(f.differences().get(0).books()).startsWith("CGST");
        assertThat(f.differences().get(0).gstr2b()).startsWith("IGST");
        assertThat(f.exposure()).isEqualByComparingTo("2000");
    }

    @Test
    void sameInvoiceUnderAnotherGstinIsGstinMismatch() {
        var r = matcher.match(List.of(booksInter(1, TUNGABHADRA_KA, "TMP/5520", "2026-08-18", "120000", "21600")),
                List.of(g2bIntra(101, TUNGABHADRA_TS, "TMP/5520", "2026-08-18", "120000", "21600")));
        var f = only(r, MismatchType.GSTIN_MISMATCH);
        assertThat(f.differences().get(0).books()).isEqualTo(TUNGABHADRA_KA);
        assertThat(f.differences().get(0).gstr2b()).isEqualTo(TUNGABHADRA_TS);
        assertThat(r.findings()).hasSize(1);
    }

    @Test
    void pureFormatDifferenceIsInvoiceNoFormatWithoutExposure() {
        var r = matcher.match(List.of(booksInter(1, GODAVARI, "142", "2026-08-08", "310000", "55800")),
                List.of(g2bInter(101, GODAVARI, "INV/26-27/0142", "2026-08-08", "310000", "55800")));
        var f = only(r, MismatchType.INVOICE_NO_FORMAT);
        assertThat(f.exposure()).isEqualByComparingTo("0");
        assertThat(f.differences()).singleElement().satisfies(d -> {
            assertThat(d.books()).isEqualTo("142");
            assertThat(d.gstr2b()).isEqualTo("INV/26-27/0142");
        });
    }

    @Test
    void differentDateOnKeyedPairIsDateMismatch() {
        var r = matcher.match(List.of(booksInter(1, "33AACCC9087F1ZM", "CPL/4471", "2026-08-21", "84000", "15120")),
                List.of(g2bInter(101, "33AACCC9087F1ZM", "CPL/4471", "2026-08-12", "84000", "15120")));
        only(r, MismatchType.DATE_MISMATCH);
    }

    @Test
    void amountDifferenceOutranksOtherDifferences() {
        var r = matcher.match(List.of(booksIntra(1, METRO, "MLS/9", "2026-08-06", "40000", "2000")),
                List.of(g2bInter(101, METRO, "MLS/0009", "2026-08-07", "45000", "2250")));
        var f = only(r, MismatchType.AMOUNT_MISMATCH);
        assertThat(f.differences()).extracting(Finding.Difference::field)
                .contains("invoice number", "invoice date", "tax head", "taxable value");
    }

    @Test
    void sameInvoiceBookedTwiceIsPossibleDuplicate() {
        var r = matcher.match(List.of(
                        booksIntra(1, "36AAKFC1914B1ZY", "CFP/3318", "2026-08-05", "72000", "12960"),
                        booksIntra(2, "36AAKFC1914B1ZY", "CFP/3318", "2026-08-05", "72000", "12960")),
                List.of(g2bIntra(101, "36AAKFC1914B1ZY", "CFP/3318", "2026-08-05", "72000", "12960")));
        assertThat(r.exactMatches()).isEqualTo(1);
        var f = only(r, MismatchType.POSSIBLE_DUPLICATE);
        assertThat(f.books().id()).isEqualTo(2);
        assertThat(f.exposure()).isEqualByComparingTo("12960");
        assertThat(f.linkedRowId()).isEqualTo(1L);
    }

    @Test
    void fuzzyCandidatesAreShownButNeverMatched() {
        var r = matcher.match(List.of(booksIntra(1, DECCAN_COPPER, "DCW/2026/1187", "2026-08-14", "215000", "38700")),
                List.of(g2bIntra(101, DECCAN_COPPER, "DCW/2026/1178", "2026-08-14", "215000", "38700")));

        assertThat(r.exactMatches()).isZero();
        var missing = only(r, MismatchType.MISSING_IN_2B);
        assertThat(missing.candidates()).singleElement().satisfies(c -> {
            assertThat(c.rowId()).isEqualTo(101);
            assertThat(c.invoiceNo()).isEqualTo("DCW/2026/1178");
            assertThat(c.normalisedNo()).isEqualTo("20261178");
            assertThat(c.reasons()).contains("same invoice date", "same amounts", "invoice number 1 character different");
        });
        var inBooks = only(r, MismatchType.MISSING_IN_BOOKS);
        assertThat(inBooks.linkedRowId()).isEqualTo(1L);
    }

    @Test
    void candidatesNeedADateInsideTheWindow() {
        var r = matcher.match(List.of(booksIntra(1, DECCAN_COPPER, "DCW/1187", "2026-08-01", "215000", "38700")),
                List.of(g2bIntra(101, DECCAN_COPPER, "DCW/1178", "2026-08-25", "215000", "38700")));
        assertThat(only(r, MismatchType.MISSING_IN_2B).candidates()).isEmpty();
    }

    @Test
    void lookAlikeVendorsAreNeverPairedEvenWithCoincidingNumbersAndAmounts() {
        // Sri Balaji Traders vs Sri Balaji Enterprises: different PANs. "SBT/0118" and "SBE-0118" both normalise to 118.
        var r = matcher.match(List.of(booksIntra(1, BALAJI_TRADERS, "SBT/0118", "2026-08-04", "93000", "16740")),
                List.of(g2bIntra(101, "36ADSFS7710L1ZD", "SBE-0118", "2026-08-04", "93000", "16740")));
        assertThat(only(r, MismatchType.MISSING_IN_2B).candidates()).isEmpty();
        assertThat(only(r, MismatchType.MISSING_IN_BOOKS).gstr2b().gstin()).isEqualTo("36ADSFS7710L1ZD");
        assertThat(r.findings()).noneMatch(f -> f.type() == MismatchType.GSTIN_MISMATCH);
    }

    @Test
    void aTypoInTheBookedGstinIsGstinMismatch() {
        // one character wrong in the books (Q1ZP -> O1ZP)
        var r = matcher.match(List.of(booksIntra(1, "36ABKFS2231O1ZP", "SBT/0120", "2026-08-04", "10000", "1800")),
                List.of(g2bIntra(101, BALAJI_TRADERS, "SBT/0120", "2026-08-04", "10000", "1800")));
        only(r, MismatchType.GSTIN_MISMATCH);
    }

    @Test
    void amendmentsAndCreditNotesAreLeftToTheOutcomeDetector() {
        var amendment = com.vishwas.support.Rows.amendmentIntra(101, METRO, "MLS/1043", "MLS/1043", "2026-05-09", "36000", "1800");
        var r = matcher.match(List.of(), List.of(amendment));
        assertThat(r.findings()).isEmpty();
        assertThat(r.gstr2bRows()).isZero();
    }

    private static Finding only(MatchResult r, MismatchType type) {
        List<Finding> of = r.findings().stream().filter(f -> f.type() == type).toList();
        assertThat(of).as("findings of type " + type + " in " + r.findings()).hasSize(1);
        return of.get(0);
    }
}

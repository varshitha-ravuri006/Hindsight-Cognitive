package com.vishwas.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceNumbersTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "INV/26-27/0142, 142",
            "142, 142",
            "0142, 142",
            "142/2026-27, 142",
            "GS-INV-0142-2026-27, 142",
            "inv 142, 142",
            "FY26-27/0142, 142",
            "KPL/0502, 502",
            "SBT/2026/0118, 20260118",
            "142A, 142A",
            "A-0007, 7",
            "'  CFP/3318 ', 3318",
            "2026-2027/88, 88",
    })
    void normalises(String raw, String expected) {
        assertThat(InvoiceNumbers.normalise(raw)).isEqualTo(expected);
    }

    @Test
    void keepsNonConsecutiveYearPairs() {
        assertThat(InvoiceNumbers.normalise("12-45")).isEqualTo("1245");
    }

    @Test
    void neverReturnsEmptyForNonBlankInput() {
        assertThat(InvoiceNumbers.normalise("26-27")).isNotEmpty();
        assertThat(InvoiceNumbers.normalise("INV")).isEqualTo("INV");
        assertThat(InvoiceNumbers.normalise(null)).isEmpty();
    }

    @Test
    void formatDifferenceIsSameKeyDifferentSpelling() {
        assertThat(InvoiceNumbers.formatDiffers("INV/26-27/0142", "142")).isTrue();
        assertThat(InvoiceNumbers.formatDiffers("142", "142")).isFalse();
        assertThat(InvoiceNumbers.formatDiffers("142", "143")).isFalse();
    }

    @Test
    void editDistanceCountsTranspositionAsOne() {
        assertThat(InvoiceNumbers.editDistance("1187", "1178")).isEqualTo(1);
        assertThat(InvoiceNumbers.editDistance("1187", "1287")).isEqualTo(1);
        assertThat(InvoiceNumbers.editDistance("1187", "9999")).isEqualTo(4);
    }

    @Test
    void similarityIgnoresVeryShortNumbersAndEqualKeys() {
        assertThat(InvoiceNumbers.similar("DCW/2026/1187", "DCW/2026/1178", 2)).isTrue();
        assertThat(InvoiceNumbers.similar("12", "13", 2)).isFalse();
        assertThat(InvoiceNumbers.similar("INV-0142", "142", 2)).isFalse();
    }
}

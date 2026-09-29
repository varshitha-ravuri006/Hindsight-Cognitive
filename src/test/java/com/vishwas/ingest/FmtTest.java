package com.vishwas.ingest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FmtTest {

    @Test
    void formatsRupeesWithIndianGrouping() {
        assertThat(Fmt.inr(new BigDecimal("142000"))).isEqualTo("Rs 1,42,000");
        assertThat(Fmt.inr(new BigDecimal("42000"))).isEqualTo("Rs 42,000");
        assertThat(Fmt.inr(new BigDecimal("12345678.5"))).isEqualTo("Rs 1,23,45,678.50");
        assertThat(Fmt.inr(new BigDecimal("999"))).isEqualTo("Rs 999");
        assertThat(Fmt.inr(new BigDecimal("-2520"))).isEqualTo("-Rs 2,520");
        assertThat(Fmt.inr(null)).isEqualTo("Rs 0");
    }

    @Test
    void labelsMonths() {
        assertThat(Fmt.month("2026-08")).isEqualTo("August 2026");
        assertThat(Fmt.day(java.time.LocalDate.of(2026, 7, 20))).isEqualTo("20 Jul 2026");
    }
}

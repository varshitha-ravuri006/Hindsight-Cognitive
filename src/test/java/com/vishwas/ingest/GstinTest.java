package com.vishwas.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GstinTest {

    @Test
    void acceptsSeedGstinsWithCorrectCheckDigits() {
        assertThat(Gstin.isValid("36AAGCD4821M1ZG")).isTrue();
        assertThat(Gstin.isValid("36ABKFS2231Q1ZP")).isTrue();
        assertThat(Gstin.isValid("36ADSFS7710L1ZD")).isTrue();
        assertThat(Gstin.isValid("29AAHCK5512D1ZO")).isTrue();
    }

    @Test
    void rejectsWrongCheckDigitAndFormat() {
        assertThat(Gstin.isValid("36ABKFS2231Q1ZQ")).isFalse();
        assertThat(Gstin.hasValidFormat("36ABKFS2231Q1ZQ")).isTrue();
        assertThat(Gstin.isValid("36ABKFS2231Q1Y")).isFalse();
        assertThat(Gstin.isValid("ABCDEFGHIJKLMNO")).isFalse();
        assertThat(Gstin.isValid(null)).isFalse();
    }

    @Test
    void stateCodeIsTheFirstTwoDigits() {
        assertThat(Gstin.stateCode("29AAHCK5512D1ZO")).isEqualTo("29");
    }
}

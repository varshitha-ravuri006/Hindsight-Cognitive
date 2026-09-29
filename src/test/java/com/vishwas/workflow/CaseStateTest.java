package com.vishwas.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseStateTest {

    @Test
    void followsTheWorkflowFromDetectedToResolved() {
        assertThat(CaseState.DETECTED.canMoveTo(CaseState.INVESTIGATING)).isTrue();
        assertThat(CaseState.INVESTIGATING.canMoveTo(CaseState.WAITING_FOR_VENDOR)).isTrue();
        assertThat(CaseState.WAITING_FOR_VENDOR.canMoveTo(CaseState.VENDOR_RESPONDED)).isTrue();
        assertThat(CaseState.VENDOR_RESPONDED.canMoveTo(CaseState.ACCOUNTANT_REVIEW)).isTrue();
        assertThat(CaseState.ACCOUNTANT_REVIEW.canMoveTo(CaseState.RESOLVED)).isTrue();
        assertThat(CaseState.ACCOUNTANT_REVIEW.canMoveTo(CaseState.ESCALATED)).isTrue();
    }

    @Test
    void refusesSkippingBackwardsOrSideways() {
        assertThat(CaseState.DETECTED.canMoveTo(CaseState.VENDOR_RESPONDED)).isFalse();
        assertThat(CaseState.RESOLVED.canMoveTo(CaseState.ESCALATED)).isFalse();
        assertThat(CaseState.ESCALATED.canMoveTo(CaseState.WAITING_FOR_VENDOR)).isFalse();
        var f = new CaseFile(1L, Instant.now(), "Lakshmi Prasad");
        assertThatThrownBy(() -> f.moveTo(CaseState.VENDOR_RESPONDED, Instant.now(), "x"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Detected to Vendor responded");
    }

    @Test
    void aResolvedCaseCanBeReopened() {
        assertThat(CaseState.RESOLVED.canMoveTo(CaseState.INVESTIGATING)).isTrue();
    }
}

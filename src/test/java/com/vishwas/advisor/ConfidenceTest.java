package com.vishwas.advisor;

import com.vishwas.matching.MismatchStatus;
import com.vishwas.matching.Outcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidenceTest {

    @Test
    void noHistoryMeansNoConfidence() {
        var c = Confidence.of(HistoryStats.empty(), 3);
        assertThat(c.level()).isEqualTo(Confidence.Level.NONE);
        assertThat(c.explanation()).contains("No past cases");
    }

    @Test
    void fewerThanThreeCasesIsThinHistory() {
        var c = Confidence.of(CategoryPolicyTest.history(Outcome.AMENDED, 2), 3);
        assertThat(c.level()).isEqualTo(Confidence.Level.LOW);
        assertThat(c.explanation()).startsWith("Thin history").contains("2 of 2");
    }

    @Test
    void consistentHistoryIsHighConfidence() {
        var c = Confidence.of(CategoryPolicyTest.history(Outcome.RESOLVED_LATE, 4), 3);
        assertThat(c.level()).isEqualTo(Confidence.Level.HIGH);
        assertThat(c.dominant()).isEqualTo(Outcome.RESOLVED_LATE);
        assertThat(c.explanation()).contains("4 of 4 past cases ended appeared late");
    }

    @Test
    void mixedHistoryIsMediumConfidence() {
        List<HistoryStats.PastCase> cases = new ArrayList<>(CategoryPolicyTest.history(Outcome.RESOLVED_LATE, 2).cases());
        cases.add(new HistoryStats.PastCase(9, "2026-07", "K", "T", new BigDecimal("15900"), MismatchStatus.AT_RISK,
                Outcome.UNRESOLVED_AT_RISK, null, null, BigDecimal.ZERO, BigDecimal.ZERO));
        cases.add(new HistoryStats.PastCase(10, "2026-04", "K0", "T", new BigDecimal("8640"), MismatchStatus.WRITTEN_OFF,
                Outcome.UNRESOLVED_AT_RISK, null, null, BigDecimal.ZERO, new BigDecimal("8640")));
        var stats = new HistoryStats(cases);
        var c = Confidence.of(stats, 3);
        assertThat(c.level()).isEqualTo(Confidence.Level.MEDIUM);
        assertThat(stats.confirmedLoss()).isEqualByComparingTo("8640");
        assertThat(stats.openExposure()).isEqualByComparingTo("15900");
    }
}

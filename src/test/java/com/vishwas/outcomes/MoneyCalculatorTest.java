package com.vishwas.outcomes;

import com.vishwas.matching.MismatchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyCalculatorTest {

    private static MoneyCalculator.Line line(String exposure, MismatchStatus status, boolean review, boolean timing,
                                             String recovered, String loss) {
        return new MoneyCalculator.Line(new BigDecimal(exposure), status, review, timing, status.open(),
                new BigDecimal(recovered), new BigDecimal(loss));
    }

    @Test
    void separatesExposureFromLossFromRecovered() {
        var s = MoneyCalculator.summarise(List.of(
                line("42000", MismatchStatus.AT_RISK, true, false, "0", "0"),        // Kaveri, open: exposure, NOT loss
                line("16740", MismatchStatus.OPEN, false, true, "0", "0"),           // Balaji Traders: likely timing
                line("1620", MismatchStatus.OPEN, false, false, "0", "0"),           // recommend, not timing
                line("8640", MismatchStatus.WRITTEN_OFF, false, false, "0", "8640"), // ITC reversed: the only loss
                line("13500", MismatchStatus.RESOLVED, false, false, "13500", "0")));// filed late: recovered

        assertThat(s.potentialExposure()).isEqualByComparingTo("60360");
        assertThat(s.needsReview()).isEqualByComparingTo("42000");
        assertThat(s.likelyTiming()).isEqualByComparingTo("16740");
        assertThat(s.confirmedLoss()).isEqualByComparingTo("8640");
        assertThat(s.recovered()).isEqualByComparingTo("13500");
        assertThat(s.openCases()).isEqualTo(3);
        assertThat(s.totalCases()).isEqualTo(5);
    }

    @Test
    void anOpenDiscrepancyIsNeverCountedAsLoss() {
        var s = MoneyCalculator.summarise(List.of(line("99999", MismatchStatus.AT_RISK, true, false, "0", "0")));
        assertThat(s.confirmedLoss()).isEqualByComparingTo("0");
        assertThat(s.potentialExposure()).isEqualByComparingTo("99999");
    }

    @Test
    void reviewTakesPrecedenceOverTimingSoNothingIsCountedTwice() {
        var s = MoneyCalculator.summarise(List.of(line("500", MismatchStatus.OPEN, true, true, "0", "0")));
        assertThat(s.needsReview()).isEqualByComparingTo("500");
        assertThat(s.likelyTiming()).isEqualByComparingTo("0");
    }

    @Test
    void emptyIsAllZero() {
        var s = MoneyCalculator.summarise(List.of());
        assertThat(s.potentialExposure()).isEqualByComparingTo("0");
        assertThat(s.attentionCases()).isZero();
    }
}

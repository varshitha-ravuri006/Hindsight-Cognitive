package com.vishwas.outcomes;

import com.vishwas.matching.MismatchStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * Money arithmetic, kept deliberately boring and separate:
 * <ul>
 *   <li><b>potential exposure</b>: ITC on cases that are still open (OPEN or AT_RISK). Not lost.</li>
 *   <li><b>confirmed loss</b>: ITC the accountant actually reversed (WRITTEN_OFF). The only "loss".</li>
 *   <li><b>recovered / resolved</b>: exposure that closed without loss (a terminal outcome).</li>
 * </ul>
 * An open discrepancy is never counted as lost.
 */
public final class MoneyCalculator {

    private MoneyCalculator() {
    }

    /**
     * One case as money sees it. {@code needsReview} and {@code likelyTiming} come from the advisor's category
     * (REQUIRE_REVIEW/ESCALATE, and a RECOMMEND whose leading cause is a timing difference).
     */
    public record Line(BigDecimal exposure, MismatchStatus status, boolean needsReview, boolean likelyTiming,
                       boolean needsAttention, BigDecimal recovered, BigDecimal confirmedLoss) {
    }

    public record Summary(BigDecimal potentialExposure, BigDecimal needsReview, BigDecimal likelyTiming,
                          BigDecimal confirmedLoss, BigDecimal recovered, int openCases, int attentionCases, int totalCases) {
    }

    public static Summary summarise(List<Line> lines) {
        BigDecimal exposure = BigDecimal.ZERO;
        BigDecimal review = BigDecimal.ZERO;
        BigDecimal timing = BigDecimal.ZERO;
        BigDecimal loss = BigDecimal.ZERO;
        BigDecimal recovered = BigDecimal.ZERO;
        int open = 0;
        int attention = 0;
        for (Line l : lines) {
            BigDecimal e = nz(l.exposure());
            if (l.status().open()) {
                open++;
                exposure = exposure.add(e);
                if (l.needsReview()) {
                    review = review.add(e);
                } else if (l.likelyTiming()) {
                    timing = timing.add(e);
                }
                if (l.needsAttention()) {
                    attention++;
                }
            }
            if (l.status() == MismatchStatus.WRITTEN_OFF) {
                loss = loss.add(nz(l.confirmedLoss()));
            }
            if (l.status() == MismatchStatus.RESOLVED) {
                recovered = recovered.add(nz(l.recovered()));
            }
        }
        return new Summary(scale(exposure), scale(review), scale(timing), scale(loss), scale(recovered), open, attention, lines.size());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}

package com.vishwas.advisor;

import com.vishwas.matching.MismatchStatus;
import com.vishwas.matching.Outcome;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the database knows about one vendor on one dimension: every past case with its month, amount and
 * outcome. This is the ledger the UI shows as evidence; memory interprets it, it never replaces it.
 */
public record HistoryStats(List<PastCase> cases) {

    /** One past case: period, invoice, exposure and (if judged) what it turned out to be. */
    public record PastCase(long mismatchId, String period, String invoiceNo, String type, BigDecimal exposure,
                           MismatchStatus status, Outcome outcome, Integer monthsLate, Long daysToResolve,
                           BigDecimal recovered, BigDecimal confirmedLoss) {
    }

    public static HistoryStats empty() {
        return new HistoryStats(List.of());
    }

    /** Cases with an outcome (terminal or at-risk) or a write-off: the ones that say something about behaviour. */
    public List<PastCase> judged() {
        return cases.stream().filter(c -> c.outcome() != null || c.status() == MismatchStatus.WRITTEN_OFF).toList();
    }

    public int judgedCount() {
        return judged().size();
    }

    public Map<Outcome, Long> outcomeCounts() {
        Map<Outcome, Long> counts = new EnumMap<>(Outcome.class);
        for (PastCase c : judged()) {
            Outcome o = c.outcome() != null ? c.outcome() : Outcome.UNRESOLVED_AT_RISK;
            counts.merge(o, 1L, Long::sum);
        }
        return counts;
    }

    public Optional<Outcome> dominantOutcome() {
        return outcomeCounts().entrySet().stream().max(Map.Entry.<Outcome, Long>comparingByValue()
                .thenComparing(e -> -e.getKey().ordinal())).map(Map.Entry::getKey);
    }

    /** Share of judged cases with the dominant outcome (0 when nothing is judged). */
    public double consistency() {
        int n = judgedCount();
        return n == 0 ? 0 : dominantOutcome().map(o -> outcomeCounts().get(o)).orElse(0L) / (double) n;
    }

    public long count(Outcome o) {
        return outcomeCounts().getOrDefault(o, 0L);
    }

    public Double averageMonthsLate() {
        return judged().stream().filter(c -> c.outcome() == Outcome.RESOLVED_LATE && c.monthsLate() != null)
                .mapToInt(PastCase::monthsLate).average().stream().boxed().findFirst().orElse(null);
    }

    public Double averageDaysToResolve() {
        return cases.stream().filter(c -> c.daysToResolve() != null && c.status() == MismatchStatus.RESOLVED)
                .mapToLong(PastCase::daysToResolve).average().stream().boxed().findFirst().orElse(null);
    }

    public BigDecimal openExposure() {
        return cases.stream().filter(c -> c.status().open()).map(PastCase::exposure).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal confirmedLoss() {
        return cases.stream().map(PastCase::confirmedLoss).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal recovered() {
        return cases.stream().filter(c -> c.status() == MismatchStatus.RESOLVED).map(PastCase::recovered)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<PastCase> chronological() {
        return cases.stream().sorted(Comparator.comparing(PastCase::period).thenComparingLong(PastCase::mismatchId)).toList();
    }
}

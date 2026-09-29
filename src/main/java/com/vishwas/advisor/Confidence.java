package com.vishwas.advisor;

import com.vishwas.matching.Outcome;

/**
 * How much Vishwas can lean on history, computed in the database from how MUCH history exists and how
 * CONSISTENT it is. Deliberately separate from the history itself, and from what the model says.
 * Past reliability never proves today's invoice is correct.
 */
public record Confidence(Level level, int cases, double consistency, Outcome dominant, String explanation) {

    public enum Level { NONE, LOW, MEDIUM, HIGH }

    public static Confidence of(HistoryStats stats, int thinHistoryCases) {
        int n = stats.judgedCount();
        if (n == 0) {
            return new Confidence(Level.NONE, 0, 0, null, "No past cases on this dimension: history cannot guide this one.");
        }
        Outcome dominant = stats.dominantOutcome().orElse(null);
        double consistency = stats.consistency();
        long same = dominant == null ? 0 : stats.count(dominant);
        String pattern = same + " of " + n + " past case" + (n == 1 ? "" : "s") + " ended "
                + (dominant == null ? "differently" : dominant.label().toLowerCase());
        if (n < thinHistoryCases) {
            return new Confidence(Level.LOW, n, consistency, dominant, "Thin history: " + pattern + ". Too few cases to rely on.");
        }
        if (consistency >= 0.8) {
            return new Confidence(Level.HIGH, n, consistency, dominant, pattern + ": a consistent pattern.");
        }
        return new Confidence(Level.MEDIUM, n, consistency, dominant, pattern + ": the pattern is mixed.");
    }
}

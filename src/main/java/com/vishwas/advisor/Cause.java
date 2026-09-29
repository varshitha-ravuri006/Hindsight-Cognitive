package com.vishwas.advisor;

import com.vishwas.matching.Outcome;

/**
 * Cause hypotheses a recommendation can rank. Each implies the outcome we expect when the next GSTR-2B
 * arrives, which is how a recommendation is later judged right or wrong (in the database, not by the LLM).
 */
public enum Cause {
    TIMING_DIFFERENCE("Timing difference", Outcome.RESOLVED_LATE),
    VENDOR_NOT_FILING("Vendor not filing", Outcome.UNRESOLVED_AT_RISK),
    DATA_ENTRY_TYPO("Data-entry or format difference", Outcome.CONFIRMED_TYPO),
    AMENDMENT_EXPECTED("Vendor amendment expected", Outcome.AMENDED),
    CREDIT_NOTE_EXPECTED("Credit note expected", Outcome.CREDIT_NOTE),
    DUPLICATE_BOOKING("Duplicate booking", Outcome.DUPLICATE),
    WRONG_GSTIN("Wrong supplier GSTIN", Outcome.AMENDED),
    UNKNOWN("Unknown", null);

    private final String label;
    private final Outcome expected;

    Cause(String label, Outcome expected) {
        this.label = label;
        this.expected = expected;
    }

    public String label() {
        return label;
    }

    /** The outcome this cause predicts, or null when it predicts nothing checkable. */
    public Outcome expectedOutcome() {
        return expected;
    }

    public static Cause parse(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(raw.trim().toUpperCase().replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}

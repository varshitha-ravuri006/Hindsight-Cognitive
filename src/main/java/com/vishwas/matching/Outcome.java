package com.vishwas.matching;

/**
 * What a mismatch turned out to be. Judged by the outcomes module when a later GSTR-2B arrives (or by an
 * explicit accountant decision). Every verdict becomes a memory.
 */
public enum Outcome {
    RESOLVED_LATE("Appeared late", true),
    AMENDED("Amended by vendor", true),
    CREDIT_NOTE("Settled by credit note", true),
    CONFIRMED_TYPO("Confirmed typo", true),
    DUPLICATE("Confirmed duplicate", true),
    UNRESOLVED_AT_RISK("Unresolved, at risk", false);

    private final String label;
    private final boolean terminal;

    Outcome(String label, boolean terminal) {
        this.label = label;
        this.terminal = terminal;
    }

    public String label() {
        return label;
    }

    /** Terminal outcomes close the case; UNRESOLVED_AT_RISK keeps it open (it can still resolve late). */
    public boolean terminal() {
        return terminal;
    }
}

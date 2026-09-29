package com.vishwas.matching;

/**
 * Money-relevant state of a mismatch.
 * <ul>
 *   <li>OPEN: not yet resolved; its exposure is potential, not lost.</li>
 *   <li>AT_RISK: still unresolved after the configured number of months; still potential exposure.</li>
 *   <li>RESOLVED: a terminal outcome was reached (recovered amounts recorded).</li>
 *   <li>WRITTEN_OFF: the accountant reversed the ITC; the exposure became a CONFIRMED loss.</li>
 * </ul>
 */
public enum MismatchStatus {
    OPEN, AT_RISK, RESOLVED, WRITTEN_OFF;

    public boolean open() {
        return this == OPEN || this == AT_RISK;
    }
}

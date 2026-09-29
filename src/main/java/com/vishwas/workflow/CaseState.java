package com.vishwas.workflow;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a case is in the accountant's work: Detected, Investigating, Waiting for vendor, Vendor responded,
 * Accountant review, then Resolved or Escalated. Only the transitions below are allowed.
 */
public enum CaseState {
    DETECTED("Detected"),
    INVESTIGATING("Investigating"),
    WAITING_FOR_VENDOR("Waiting for vendor"),
    VENDOR_RESPONDED("Vendor responded"),
    ACCOUNTANT_REVIEW("Accountant review"),
    RESOLVED("Resolved"),
    ESCALATED("Escalated");

    private final String label;

    CaseState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public Set<CaseState> next() {
        return switch (this) {
            case DETECTED -> EnumSet.of(INVESTIGATING, WAITING_FOR_VENDOR, ACCOUNTANT_REVIEW, RESOLVED, ESCALATED);
            case INVESTIGATING -> EnumSet.of(WAITING_FOR_VENDOR, ACCOUNTANT_REVIEW, RESOLVED, ESCALATED);
            case WAITING_FOR_VENDOR -> EnumSet.of(VENDOR_RESPONDED, ACCOUNTANT_REVIEW, RESOLVED, ESCALATED);
            case VENDOR_RESPONDED -> EnumSet.of(ACCOUNTANT_REVIEW, WAITING_FOR_VENDOR, RESOLVED, ESCALATED);
            case ACCOUNTANT_REVIEW -> EnumSet.of(RESOLVED, ESCALATED, WAITING_FOR_VENDOR, INVESTIGATING);
            case ESCALATED -> EnumSet.of(ACCOUNTANT_REVIEW, RESOLVED);
            case RESOLVED -> EnumSet.of(INVESTIGATING);
        };
    }

    public boolean canMoveTo(CaseState to) {
        return next().contains(to);
    }

    public boolean closed() {
        return this == RESOLVED;
    }
}

package com.vishwas.outcomes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Something the accountant (or CFO) did on a case: a note, an ITC write-off (the only source of a confirmed
 * loss), an approved auto-resolution or its reversal, a state change. Kept as an audit trail.
 */
@Entity
@Table(name = "accountant_action")
public class AccountantAction {

    public static final String NOTE = "NOTE";
    public static final String WRITE_OFF = "WRITE_OFF";
    public static final String AUTO_RESOLVE_APPLIED = "AUTO_RESOLVE_APPLIED";
    public static final String AUTO_RESOLVE_REVERSED = "AUTO_RESOLVE_REVERSED";
    public static final String STATE_CHANGE = "STATE_CHANGE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 15)
    private String vendorGstin;

    private Long mismatchId;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false, length = 30)
    private String action;

    @Column(length = 1000)
    private String note;

    private String approvedBy;

    protected AccountantAction() {
    }

    public AccountantAction(String vendorGstin, Long mismatchId, Instant occurredAt, String actor, String action, String note,
                            String approvedBy) {
        this.vendorGstin = vendorGstin;
        this.mismatchId = mismatchId;
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.action = action;
        this.note = note;
        this.approvedBy = approvedBy;
    }

    public Long getId() {
        return id;
    }

    public String getVendorGstin() {
        return vendorGstin;
    }

    public Long getMismatchId() {
        return mismatchId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getNote() {
        return note;
    }

    public String getApprovedBy() {
        return approvedBy;
    }
}

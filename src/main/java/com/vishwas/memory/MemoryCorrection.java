package com.vishwas.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Audit row for "Correct this history": who changed which memory, when, why, and the text before and after.
 * The row is written before the change is sent to Hindsight, so even a failed correction is on record.
 */
@Entity
@Table(name = "memory_correction")
public class MemoryCorrection {

    public enum Action { EDIT, INVALIDATE, REVERT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String memoryId;

    @Column(length = 15)
    private String vendorGstin;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Action action;

    @Column(length = 4000)
    private String oldText;

    @Column(length = 4000)
    private String newText;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private Instant createdAt;

    /** PENDING, DONE or FAILED. */
    @Column(nullable = false, length = 12)
    private String status;

    @Column(length = 1000)
    private String error;

    protected MemoryCorrection() {
    }

    public MemoryCorrection(String memoryId, String vendorGstin, Action action, String oldText, String newText, String reason,
                            String actor, Instant createdAt) {
        this.memoryId = memoryId;
        this.vendorGstin = vendorGstin;
        this.action = action;
        this.oldText = clip(oldText);
        this.newText = clip(newText);
        this.reason = reason;
        this.actor = actor;
        this.createdAt = createdAt;
        this.status = "PENDING";
    }

    public void done() {
        this.status = "DONE";
    }

    public void failed(String error) {
        this.status = "FAILED";
        this.error = error == null ? null : error.substring(0, Math.min(1000, error.length()));
    }

    private static String clip(String s) {
        return s == null || s.length() <= 4000 ? s : s.substring(0, 4000);
    }

    public Long getId() {
        return id;
    }

    public String getMemoryId() {
        return memoryId;
    }

    public String getVendorGstin() {
        return vendorGstin;
    }

    public Action getAction() {
        return action;
    }

    public String getOldText() {
        return oldText;
    }

    public String getNewText() {
        return newText;
    }

    public String getReason() {
        return reason;
    }

    public String getActor() {
        return actor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }
}

package com.vishwas.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** The accountant's working state of one case: state, owner and due date. Created on first touch. */
@Entity
@Table(name = "case_file")
public class CaseFile {

    @Id
    private Long mismatchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CaseState state;

    private String owner;

    private LocalDate dueDate;

    @Column(nullable = false)
    private Instant updatedAt;

    private String updatedBy;

    protected CaseFile() {
    }

    public CaseFile(Long mismatchId, Instant at, String by) {
        this.mismatchId = mismatchId;
        this.state = CaseState.DETECTED;
        this.updatedAt = at;
        this.updatedBy = by;
    }

    public void moveTo(CaseState to, Instant at, String by) {
        if (!state.canMoveTo(to)) {
            throw new IllegalStateException("A case cannot move from " + state.label() + " to " + to.label() + ".");
        }
        this.state = to;
        touch(at, by);
    }

    public void assign(String owner, LocalDate due, Instant at, String by) {
        this.owner = owner == null || owner.isBlank() ? null : owner.trim();
        this.dueDate = due;
        touch(at, by);
    }

    private void touch(Instant at, String by) {
        this.updatedAt = at;
        this.updatedBy = by;
    }

    public Long getMismatchId() {
        return mismatchId;
    }

    public CaseState getState() {
        return state;
    }

    public String getOwner() {
        return owner;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}

package com.vishwas.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** A follow-up reminder on a case ("chase Kaveri again if nothing by 7 Oct"). */
@Entity
@Table(name = "reminder")
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long mismatchId;

    @Column(nullable = false)
    private LocalDate dueOn;

    @Column(nullable = false, length = 500)
    private String text;

    private boolean done;

    @Column(nullable = false)
    private Instant createdAt;

    private String createdBy;

    protected Reminder() {
    }

    public Reminder(Long mismatchId, LocalDate dueOn, String text, Instant createdAt, String createdBy) {
        this.mismatchId = mismatchId;
        this.dueOn = dueOn;
        this.text = text;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public void complete() {
        this.done = true;
    }

    public boolean overdue(LocalDate today) {
        return !done && dueOn.isBefore(today);
    }

    public Long getId() {
        return id;
    }

    public Long getMismatchId() {
        return mismatchId;
    }

    public LocalDate getDueOn() {
        return dueOn;
    }

    public String getText() {
        return text;
    }

    public boolean isDone() {
        return done;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}

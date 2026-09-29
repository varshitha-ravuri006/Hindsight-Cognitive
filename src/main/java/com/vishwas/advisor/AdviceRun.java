package com.vishwas.advisor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One advice run over a period's open cases: progress for the UI and timing for the record. */
@Entity
@Table(name = "advice_run")
public class AdviceRun {

    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 7)
    private String period;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    @Column(nullable = false, length = 10)
    private String status;

    /** MEMORY or TEXTBOOK. */
    @Column(nullable = false, length = 10)
    private String mode;

    private int vendorsTotal;

    private int vendorsDone;

    @Column(length = 1000)
    private String message;

    private Long durationMs;

    protected AdviceRun() {
    }

    public AdviceRun(String period, Instant startedAt, String mode, int vendorsTotal) {
        this.period = period;
        this.startedAt = startedAt;
        this.status = RUNNING;
        this.mode = mode;
        this.vendorsTotal = vendorsTotal;
    }

    public void progress(int done) {
        this.vendorsDone = done;
    }

    public void finish(String status, Instant at, String message) {
        this.status = status;
        this.finishedAt = at;
        this.message = message == null ? null : message.substring(0, Math.min(1000, message.length()));
        this.durationMs = at.toEpochMilli() - startedAt.toEpochMilli();
    }

    public Long getId() {
        return id;
    }

    public String getPeriod() {
        return period;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getStatus() {
        return status;
    }

    public String getMode() {
        return mode;
    }

    public int getVendorsTotal() {
        return vendorsTotal;
    }

    public int getVendorsDone() {
        return vendorsDone;
    }

    public String getMessage() {
        return message;
    }

    public Long getDurationMs() {
        return durationMs;
    }
}

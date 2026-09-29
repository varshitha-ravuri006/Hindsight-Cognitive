package com.vishwas.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One chronological batch of memories sent to Hindsight (a month of history, or a live step). Its wall-clock
 * window lets the observation history be labelled by the month whose facts caused each change of belief.
 */
@Entity
@Table(name = "memory_batch")
public class MemoryBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String label;

    @Column(length = 7)
    private String period;

    private int items;

    @Column(nullable = false, length = 12)
    private String status;

    @Column(length = 80)
    private String operationId;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    protected MemoryBatch() {
    }

    public MemoryBatch(String label, String period, int items, Instant startedAt) {
        this.label = label;
        this.period = period;
        this.items = items;
        this.status = "RUNNING";
        this.startedAt = startedAt;
    }

    public void operation(String operationId) {
        this.operationId = operationId;
    }

    public void finish(String status, Instant at) {
        this.status = status;
        this.finishedAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getPeriod() {
        return period;
    }

    public int getItems() {
        return items;
    }

    public String getStatus() {
        return status;
    }

    public String getOperationId() {
        return operationId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}

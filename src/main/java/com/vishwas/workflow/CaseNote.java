package com.vishwas.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A free-text note on a case. */
@Entity
@Table(name = "case_note")
public class CaseNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long mismatchId;

    @Column(nullable = false)
    private String author;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 2000)
    private String text;

    protected CaseNote() {
    }

    public CaseNote(Long mismatchId, String author, Instant createdAt, String text) {
        this.mismatchId = mismatchId;
        this.author = author;
        this.createdAt = createdAt;
        this.text = text;
    }

    public Long getId() {
        return id;
    }

    public Long getMismatchId() {
        return mismatchId;
    }

    public String getAuthor() {
        return author;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getText() {
        return text;
    }
}

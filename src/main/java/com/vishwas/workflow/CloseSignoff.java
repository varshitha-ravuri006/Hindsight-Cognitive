package com.vishwas.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A manual month-end sign-off (the final review), with who signed and when. */
@Entity
@Table(name = "close_signoff")
public class CloseSignoff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 7)
    private String period;

    @Column(nullable = false, length = 30)
    private String item;

    @Column(nullable = false)
    private String signedBy;

    @Column(nullable = false)
    private Instant signedAt;

    @Column(length = 1000)
    private String note;

    protected CloseSignoff() {
    }

    public CloseSignoff(String period, String item, String signedBy, Instant signedAt, String note) {
        this.period = period;
        this.item = item;
        this.signedBy = signedBy;
        this.signedAt = signedAt;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public String getPeriod() {
        return period;
    }

    public String getItem() {
        return item;
    }

    public String getSignedBy() {
        return signedBy;
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public String getNote() {
        return note;
    }
}

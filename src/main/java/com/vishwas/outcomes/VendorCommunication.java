package com.vishwas.outcomes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * One message in a vendor thread: a follow-up we sent, a reply, a call or a letter. A message can carry a
 * promise ("will file by 20 July"), which {@link PromiseTracker} later judges KEPT or BROKEN from the data.
 */
@Entity
@Table(name = "vendor_communication")
public class VendorCommunication {

    public enum Direction { OUT, IN }

    public enum Channel { EMAIL, PHONE, LETTER, MEETING }

    public enum PromiseStatus { PENDING, KEPT, BROKEN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 15)
    private String vendorGstin;

    @Column(nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Channel channel;

    private String author;

    @Column(nullable = false, length = 1000)
    private String summary;

    /** Comma-separated invoice numbers as booked. */
    @Column(length = 500)
    private String invoiceRefs;

    private LocalDate promiseBy;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private PromiseStatus promiseStatus;

    private Instant promiseCheckedAt;

    /** File name of a letter (served from the seed letters or uploads). */
    private String attachment;

    /** JOURNAL (seeded history) or APP (recorded in Vishwas). */
    @Column(nullable = false, length = 10)
    private String source;

    protected VendorCommunication() {
    }

    public VendorCommunication(String vendorGstin, Instant occurredAt, Direction direction, Channel channel, String author,
                               String summary, List<String> invoices, LocalDate promiseBy, String attachment, String source) {
        this.vendorGstin = vendorGstin;
        this.occurredAt = occurredAt;
        this.direction = direction;
        this.channel = channel;
        this.author = author;
        this.summary = summary;
        this.invoiceRefs = invoices == null || invoices.isEmpty() ? null : String.join(",", invoices);
        this.promiseBy = promiseBy;
        this.promiseStatus = promiseBy == null ? null : PromiseStatus.PENDING;
        this.attachment = attachment;
        this.source = source;
    }

    public void settlePromise(PromiseStatus status, Instant at) {
        this.promiseStatus = status;
        this.promiseCheckedAt = at;
    }

    public List<String> invoices() {
        return invoiceRefs == null ? List.of() : Arrays.stream(invoiceRefs.split(",")).map(String::trim).toList();
    }

    public Long getId() {
        return id;
    }

    public String getVendorGstin() {
        return vendorGstin;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Direction getDirection() {
        return direction;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getAuthor() {
        return author;
    }

    public String getSummary() {
        return summary;
    }

    public String getInvoiceRefs() {
        return invoiceRefs;
    }

    public LocalDate getPromiseBy() {
        return promiseBy;
    }

    public PromiseStatus getPromiseStatus() {
        return promiseStatus;
    }

    public Instant getPromiseCheckedAt() {
        return promiseCheckedAt;
    }

    public String getAttachment() {
        return attachment;
    }

    public String getSource() {
        return source;
    }
}

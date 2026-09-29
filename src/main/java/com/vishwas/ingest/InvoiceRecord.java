package com.vishwas.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A stored invoice line from a purchase register or a GSTR-2B (invoice, amendment or credit note). */
@Entity
@Table(name = "invoice_record")
public class InvoiceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false, length = 7)
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InvoiceRow.Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private InvoiceRow.Kind kind;

    @Column(nullable = false, length = 15)
    private String supplierGstin;

    private String supplierName;

    @Column(nullable = false, length = 40)
    private String invoiceNo;

    @Column(nullable = false, length = 40)
    private String invoiceNoNorm;

    private LocalDate invoiceDate;

    @Column(precision = 14, scale = 2)
    private BigDecimal taxableValue;

    @Column(precision = 14, scale = 2)
    private BigDecimal igst;

    @Column(precision = 14, scale = 2)
    private BigDecimal cgst;

    @Column(precision = 14, scale = 2)
    private BigDecimal sgst;

    @Column(precision = 5, scale = 2)
    private BigDecimal rate;

    @Column(length = 10)
    private String hsn;

    private String description;

    @Column(length = 2)
    private String placeOfSupply;

    /** Purchase register only. */
    @Column(length = 30)
    private String voucherNo;

    private LocalDate bookingDate;

    /** GSTR-2B only: when the supplier filed the GSTR-1 that carried this row, and for which period. */
    private LocalDate filedOn;

    @Column(length = 7)
    private String filingPeriod;

    /** Amendments and credit notes: the invoice they refer to. */
    @Column(length = 40)
    private String originalInvoiceNo;

    private LocalDate originalInvoiceDate;

    protected InvoiceRecord() {
    }

    public static InvoiceRecord of(long batchId, ParsedLine p) {
        InvoiceRecord r = new InvoiceRecord();
        r.batchId = batchId;
        r.period = p.row().period();
        r.source = p.row().source();
        r.kind = p.row().kind();
        r.supplierGstin = p.row().gstin();
        r.supplierName = p.row().supplierName();
        r.invoiceNo = p.row().invoiceNo();
        r.invoiceNoNorm = p.row().normalisedNo();
        r.invoiceDate = p.row().invoiceDate();
        r.taxableValue = p.row().taxableValue();
        r.igst = p.row().igst();
        r.cgst = p.row().cgst();
        r.sgst = p.row().sgst();
        r.originalInvoiceNo = p.row().originalInvoiceNo();
        r.originalInvoiceDate = p.row().originalInvoiceDate();
        r.rate = p.rate();
        r.hsn = p.hsn();
        r.description = p.description();
        r.placeOfSupply = p.placeOfSupply();
        r.voucherNo = p.voucherNo();
        r.bookingDate = p.bookingDate();
        r.filedOn = p.filedOn();
        r.filingPeriod = p.filingPeriod();
        return r;
    }

    public InvoiceRow toRow() {
        return new InvoiceRow(id, source, kind, period, supplierGstin, supplierName, invoiceNo, invoiceDate, taxableValue,
                igst, cgst, sgst, originalInvoiceNo, originalInvoiceDate);
    }

    public Long getId() {
        return id;
    }

    public Long getBatchId() {
        return batchId;
    }

    public String getPeriod() {
        return period;
    }

    public InvoiceRow.Source getSource() {
        return source;
    }

    public InvoiceRow.Kind getKind() {
        return kind;
    }

    public String getSupplierGstin() {
        return supplierGstin;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public String getInvoiceNo() {
        return invoiceNo;
    }

    public String getInvoiceNoNorm() {
        return invoiceNoNorm;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public BigDecimal getTaxableValue() {
        return taxableValue;
    }

    public BigDecimal getIgst() {
        return igst;
    }

    public BigDecimal getCgst() {
        return cgst;
    }

    public BigDecimal getSgst() {
        return sgst;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public String getHsn() {
        return hsn;
    }

    public String getDescription() {
        return description;
    }

    public String getPlaceOfSupply() {
        return placeOfSupply;
    }

    public String getVoucherNo() {
        return voucherNo;
    }

    public LocalDate getBookingDate() {
        return bookingDate;
    }

    public LocalDate getFiledOn() {
        return filedOn;
    }

    public String getFilingPeriod() {
        return filingPeriod;
    }

    public String getOriginalInvoiceNo() {
        return originalInvoiceNo;
    }

    public LocalDate getOriginalInvoiceDate() {
        return originalInvoiceDate;
    }
}

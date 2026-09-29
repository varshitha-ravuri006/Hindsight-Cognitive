package com.vishwas.matching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A stored mismatch and its whole life: detection, original vs GSTR-2B values, exposure, and later the verdict,
 * how many months late it resolved, what was recovered and what (if anything) became a confirmed loss.
 */
@Entity
@Table(name = "mismatch")
public class Mismatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Return period in which it was detected (yyyy-MM). */
    @Column(nullable = false, length = 7)
    private String period;

    @Column(nullable = false, length = 15)
    private String vendorGstin;

    private String vendorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private MismatchType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Dimension dimension;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private MismatchStatus status;

    private Long booksRecordId;
    private Long gstr2bRecordId;

    @Column(length = 40)
    private String invoiceNoBooks;
    @Column(length = 40)
    private String invoiceNoGstr2b;
    @Column(length = 40)
    private String invoiceNoNorm;

    private LocalDate invoiceDateBooks;
    private LocalDate invoiceDateGstr2b;

    @Column(precision = 14, scale = 2)
    private BigDecimal taxableBooks;
    @Column(precision = 14, scale = 2)
    private BigDecimal taxableGstr2b;
    @Column(precision = 14, scale = 2)
    private BigDecimal itcBooks;
    @Column(precision = 14, scale = 2)
    private BigDecimal itcGstr2b;

    /** Potential ITC exposure at detection. Never a loss while the case is open. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal exposure;

    @Column(length = 4000)
    private String differencesJson;

    @Column(length = 8000)
    private String candidatesJson;

    /** For MISSING_IN_BOOKS: the mismatch of the books row this 2B row is a candidate for. */
    private Long linkedMismatchId;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private Instant detectedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private Outcome verdict;

    @Column(length = 7)
    private String verdictPeriod;

    private Instant verdictAt;

    private Integer monthsLate;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal recoveredAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal confirmedLoss = BigDecimal.ZERO;

    @Column(length = 500)
    private String verdictNote;

    /** The later invoice row that proved the verdict (it is then not reconciled again as a new row). */
    private Long evidenceRecordId;

    protected Mismatch() {
    }

    public static Mismatch detected(String period, Finding f, String vendorName, Instant detectedAt,
                                    String differencesJson, String candidatesJson) {
        Mismatch m = new Mismatch();
        m.period = period;
        var primary = f.primary();
        m.vendorGstin = f.books() != null ? f.books().gstin() : f.gstr2b().gstin();
        m.vendorName = vendorName != null ? vendorName : primary.supplierName();
        m.type = f.type();
        m.dimension = f.type().dimension();
        m.status = MismatchStatus.OPEN;
        if (f.books() != null) {
            m.booksRecordId = f.books().id();
            m.invoiceNoBooks = f.books().invoiceNo();
            m.invoiceDateBooks = f.books().invoiceDate();
            m.taxableBooks = f.books().taxableValue();
            m.itcBooks = f.books().itc();
        }
        if (f.gstr2b() != null) {
            m.gstr2bRecordId = f.gstr2b().id();
            m.invoiceNoGstr2b = f.gstr2b().invoiceNo();
            m.invoiceDateGstr2b = f.gstr2b().invoiceDate();
            m.taxableGstr2b = f.gstr2b().taxableValue();
            m.itcGstr2b = f.gstr2b().itc();
        }
        m.invoiceNoNorm = primary.normalisedNo();
        m.exposure = f.exposure();
        m.differencesJson = differencesJson;
        m.candidatesJson = candidatesJson;
        m.note = f.note();
        m.detectedAt = detectedAt;
        return m;
    }

    /** Record a verdict. Terminal outcomes resolve the case; UNRESOLVED_AT_RISK keeps it open. */
    public void judge(Outcome outcome, String judgedInPeriod, Instant at, Integer monthsLate, BigDecimal recovered, String note,
                      Long evidenceRecordId) {
        if (evidenceRecordId != null && evidenceRecordId != 0) {
            this.evidenceRecordId = evidenceRecordId;
        }
        this.verdict = outcome;
        this.verdictPeriod = judgedInPeriod;
        this.verdictAt = at;
        this.monthsLate = monthsLate;
        this.verdictNote = note;
        if (outcome.terminal()) {
            this.status = MismatchStatus.RESOLVED;
            this.recoveredAmount = recovered == null ? BigDecimal.ZERO : recovered;
        } else {
            this.status = MismatchStatus.AT_RISK;
        }
    }

    /**
     * The accountant reversed the ITC: the open exposure becomes a confirmed loss. The verdict that led here
     * (usually UNRESOLVED_AT_RISK) and its date are kept; the write-off itself is an accountant action.
     */
    public void writeOff(Instant at, String note) {
        this.status = MismatchStatus.WRITTEN_OFF;
        this.confirmedLoss = exposure;
    }

    /** Undo a verdict (used when an auto-resolution is reversed). */
    public void reopen() {
        this.status = MismatchStatus.OPEN;
        this.verdict = null;
        this.verdictPeriod = null;
        this.verdictAt = null;
        this.monthsLate = null;
        this.recoveredAmount = BigDecimal.ZERO;
        this.verdictNote = null;
        this.evidenceRecordId = null;
    }

    public Long getEvidenceRecordId() {
        return evidenceRecordId;
    }

    public void linkTo(Long mismatchId) {
        this.linkedMismatchId = mismatchId;
    }

    /** The invoice number to show: the books number when there is one. */
    public String invoiceNo() {
        return invoiceNoBooks != null ? invoiceNoBooks : invoiceNoGstr2b;
    }

    public Long getId() {
        return id;
    }

    public String getPeriod() {
        return period;
    }

    public String getVendorGstin() {
        return vendorGstin;
    }

    public String getVendorName() {
        return vendorName;
    }

    public MismatchType getType() {
        return type;
    }

    public Dimension getDimension() {
        return dimension;
    }

    public MismatchStatus getStatus() {
        return status;
    }

    public Long getBooksRecordId() {
        return booksRecordId;
    }

    public Long getGstr2bRecordId() {
        return gstr2bRecordId;
    }

    public String getInvoiceNoBooks() {
        return invoiceNoBooks;
    }

    public String getInvoiceNoGstr2b() {
        return invoiceNoGstr2b;
    }

    public String getInvoiceNoNorm() {
        return invoiceNoNorm;
    }

    public LocalDate getInvoiceDateBooks() {
        return invoiceDateBooks;
    }

    public LocalDate getInvoiceDateGstr2b() {
        return invoiceDateGstr2b;
    }

    public BigDecimal getTaxableBooks() {
        return taxableBooks;
    }

    public BigDecimal getTaxableGstr2b() {
        return taxableGstr2b;
    }

    public BigDecimal getItcBooks() {
        return itcBooks;
    }

    public BigDecimal getItcGstr2b() {
        return itcGstr2b;
    }

    public BigDecimal getExposure() {
        return exposure;
    }

    public String getDifferencesJson() {
        return differencesJson;
    }

    public String getCandidatesJson() {
        return candidatesJson;
    }

    public Long getLinkedMismatchId() {
        return linkedMismatchId;
    }

    public String getNote() {
        return note;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Outcome getVerdict() {
        return verdict;
    }

    public String getVerdictPeriod() {
        return verdictPeriod;
    }

    public Instant getVerdictAt() {
        return verdictAt;
    }

    public Integer getMonthsLate() {
        return monthsLate;
    }

    public BigDecimal getRecoveredAmount() {
        return recoveredAmount;
    }

    public BigDecimal getConfirmedLoss() {
        return confirmedLoss;
    }

    public String getVerdictNote() {
        return verdictNote;
    }
}

package com.vishwas.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One imported file: a purchase register or a GSTR-2B for a return period. Re-importing replaces it. */
@Entity
@Table(name = "import_batch")
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 7)
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InvoiceRow.Source source;

    private String filename;

    private int rowCount;

    @Column(nullable = false)
    private Instant importedAt;

    @Column(length = 64)
    private String sha256;

    protected ImportBatch() {
    }

    public ImportBatch(String period, InvoiceRow.Source source, String filename, int rowCount, Instant importedAt, String sha256) {
        this.period = period;
        this.source = source;
        this.filename = filename;
        this.rowCount = rowCount;
        this.importedAt = importedAt;
        this.sha256 = sha256;
    }

    public Long getId() {
        return id;
    }

    public String getPeriod() {
        return period;
    }

    public InvoiceRow.Source getSource() {
        return source;
    }

    public String getFilename() {
        return filename;
    }

    public int getRowCount() {
        return rowCount;
    }

    public Instant getImportedAt() {
        return importedAt;
    }

    public String getSha256() {
        return sha256;
    }
}

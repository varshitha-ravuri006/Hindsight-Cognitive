package com.vishwas.ingest;

import com.vishwas.config.VishwasProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.List;

/**
 * Stores monthly inputs. Importing the same period and source again replaces the earlier batch, so imports
 * are idempotent and a corrected file can simply be re-uploaded.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    public record ImportSummary(long batchId, String period, InvoiceRow.Source source, String filename, int rows,
                                List<String> warnings, boolean unchanged) {
    }

    private final ImportBatchRepository batches;
    private final InvoiceRecordRepository records;
    private final VishwasProperties props;
    private final Clock clock;
    private final PurchaseRegisterParser registerParser = new PurchaseRegisterParser();
    private final Gstr2bParser gstr2bParser = new Gstr2bParser();

    public IngestService(ImportBatchRepository batches, InvoiceRecordRepository records, VishwasProperties props, Clock clock) {
        this.batches = batches;
        this.records = records;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public ImportSummary importFile(String period, InvoiceRow.Source source, String filename, byte[] bytes) {
        YearMonth.parse(period);
        String sha = sha256(bytes);
        var existing = batches.findByPeriodAndSource(period, source);
        if (existing.isPresent() && sha.equals(existing.get().getSha256())) {
            ImportBatch b = existing.get();
            return new ImportSummary(b.getId(), period, source, b.getFilename(), b.getRowCount(), List.of(), true);
        }
        ParseResult parsed = source == InvoiceRow.Source.BOOKS
                ? registerParser.parse(bytes, period, props.company().stateCode())
                : gstr2bParser.parse(bytes, period, props.company().gstin());

        existing.ifPresent(b -> {
            records.deleteByBatchId(b.getId());
            batches.delete(b);
            batches.flush();
        });
        ImportBatch batch = batches.save(new ImportBatch(period, source, filename, parsed.lines().size(), Instant.now(clock), sha));
        records.saveAll(parsed.lines().stream().map(l -> InvoiceRecord.of(batch.getId(), l)).toList());
        log.info("Imported {} {} rows for {} from {} ({} warnings)", parsed.lines().size(), source, period, filename,
                parsed.warnings().size());
        return new ImportSummary(batch.getId(), period, source, filename, parsed.lines().size(), parsed.warnings(), false);
    }

    @Transactional(readOnly = true)
    public List<InvoiceRow> rows(String period, InvoiceRow.Source source) {
        return records.findByPeriodAndSourceOrderByIdAsc(period, source).stream().map(InvoiceRecord::toRow).toList();
    }

    public boolean imported(String period, InvoiceRow.Source source) {
        return batches.findByPeriodAndSource(period, source).isPresent();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

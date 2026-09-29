package com.vishwas.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.vishwas.advisor.Recommendation;
import com.vishwas.advisor.RecommendationRepository;
import com.vishwas.ingest.Fmt;
import com.vishwas.ingest.IngestService;
import com.vishwas.ingest.InvoiceRecordRepository;
import com.vishwas.ingest.InvoiceRow;
import com.vishwas.ingest.Vendor;
import com.vishwas.ingest.VendorRepository;
import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchRepository;
import com.vishwas.matching.MismatchStatus;
import com.vishwas.matching.MismatchType;
import com.vishwas.memory.HistoryMemoryLoader;
import com.vishwas.memory.KnowledgePages;
import com.vishwas.memory.MemoryBatchRepository;
import com.vishwas.memory.MemoryPublisher;
import com.vishwas.memory.MemoryWriter;
import com.vishwas.memory.MentalModels;
import com.vishwas.memory.hindsight.FileUpload;
import com.vishwas.memory.hindsight.MemoryItem;
import com.vishwas.outcomes.AccountantAction;
import com.vishwas.outcomes.AccountantActionRepository;
import com.vishwas.outcomes.VendorCommunication;
import com.vishwas.outcomes.VendorCommunicationRepository;
import com.vishwas.workflow.MonthProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 1, "Load 4 months of history (Apr to Jul 2026)". First the database: each month is imported and
 * reconciled on the date it really happened (the 15th of the following month), and the journal of
 * communications, promises, recommendations and decisions is replayed in between. Then memory: the same
 * history is sent to Hindsight one month per batch with real backdated timestamps, waiting for consolidation
 * after each month so the beliefs evolve. Idempotent: an already loaded database or memory is not reloaded.
 */
@Service
public class HistoryLoader {

    private static final Logger log = LoggerFactory.getLogger(HistoryLoader.class);

    public record Summary(int invoices, int mismatches, int vendorsWithIssues, int communications, int promisesKept,
                          int promisesBroken, int recommendations, BigDecimal openExposure, BigDecimal confirmedLoss,
                          BigDecimal recovered, String line) {
    }

    public record Status(String stage, boolean databaseLoaded, boolean memoryLoaded, boolean running,
                         HistoryMemoryLoader.Progress memory, Summary summary, String error, boolean memoryEnabled) {
    }

    private final SeedCatalog seed;
    private final IngestService ingest;
    private final MonthProcessor processor;
    private final JournalReplayer journal;
    private final VendorRepository vendors;
    private final MismatchRepository mismatches;
    private final InvoiceRecordRepository records;
    private final VendorCommunicationRepository communications;
    private final AccountantActionRepository actions;
    private final RecommendationRepository recommendations;
    private final MemoryWriter writer;
    private final MemoryPublisher publisher;
    private final HistoryMemoryLoader memoryLoader;
    private final MemoryBatchRepository memoryBatches;
    private final MentalModels mentalModels;
    private final KnowledgePages pages;

    private volatile String stage = "idle";
    private volatile boolean running;
    private volatile String error;

    public HistoryLoader(SeedCatalog seed, IngestService ingest, MonthProcessor processor, JournalReplayer journal,
                         VendorRepository vendors, MismatchRepository mismatches, InvoiceRecordRepository records,
                         VendorCommunicationRepository communications, AccountantActionRepository actions,
                         RecommendationRepository recommendations, MemoryWriter writer, MemoryPublisher publisher,
                         HistoryMemoryLoader memoryLoader, MemoryBatchRepository memoryBatches, MentalModels mentalModels,
                         KnowledgePages pages) {
        this.seed = seed;
        this.ingest = ingest;
        this.processor = processor;
        this.journal = journal;
        this.vendors = vendors;
        this.mismatches = mismatches;
        this.records = records;
        this.communications = communications;
        this.actions = actions;
        this.recommendations = recommendations;
        this.writer = writer;
        this.publisher = publisher;
        this.memoryLoader = memoryLoader;
        this.memoryBatches = memoryBatches;
        this.mentalModels = mentalModels;
        this.pages = pages;
    }

    /** Starts (or resumes) the load in the background. */
    public synchronized Status start() {
        if (running || memoryLoader.progress().busy()) {
            return status();
        }
        running = true;
        error = null;
        Thread.ofVirtual().name("history-load").start(() -> {
            try {
                if (!databaseLoaded()) {
                    replayDatabase();
                }
                if (publisher.enabled() && !memoryLoaded()) {
                    stage = "memory";
                    memoryLoader.load(batches(), this::afterMemory);
                } else {
                    stage = "done";
                }
            } catch (RuntimeException e) {
                log.error("History load failed", e);
                error = e.getMessage();
                stage = "failed";
            } finally {
                running = false;
            }
        });
        return status();
    }

    /** Database only, synchronously (tests and the snapshot recorder). */
    public void replayDatabaseNow() {
        if (!databaseLoaded()) {
            replayDatabase();
        }
    }

    public Status status() {
        boolean db = databaseLoaded();
        return new Status(stage, db, memoryLoaded(), running, memoryLoader.progress(), db ? summary() : null, error, publisher.enabled());
    }

    public boolean databaseLoaded() {
        return ingest.imported(SeedCatalog.HISTORY_PERIODS.get(SeedCatalog.HISTORY_PERIODS.size() - 1), InvoiceRow.Source.GSTR2B)
                && mismatches.countByPeriod(SeedCatalog.HISTORY_PERIODS.get(0)) > 0;
    }

    public boolean memoryLoaded() {
        return memoryBatches.findAllByOrderByStartedAtAsc().stream()
                .filter(b -> "DONE".equals(b.getStatus()) && SeedCatalog.HISTORY_PERIODS.contains(b.getPeriod())
                        && b.getLabel().startsWith("History"))
                .count() >= SeedCatalog.HISTORY_PERIODS.size();
    }

    // ---------------------------------------------------------------- database replay

    void replayDatabase() {
        stage = "vendors";
        seedVendors();
        JsonNode events = seed.journal();
        List<String> periods = SeedCatalog.HISTORY_PERIODS;
        for (int i = 0; i < periods.size(); i++) {
            String p = periods.get(i);
            stage = "reconciling " + Fmt.month(p);
            ingest.importFile(p, InvoiceRow.Source.BOOKS, SeedCatalog.BOOKS_FILE, seed.books(p));
            ingest.importFile(p, InvoiceRow.Source.GSTR2B, SeedCatalog.GSTR2B_FILE, seed.gstr2b(p));
            processor.process(p, cycleAt(p), MonthProcessor.Mode.RECONCILE, false);
            for (JsonNode e : JournalReplayer.window(events, cycleAt(p), cycleAt(next(p)))) {
                journal.apply(e);
            }
        }
        stage = "database ready";
        log.info("History replayed into the database: {}", summary().line());
    }

    public void seedVendors() {
        for (JsonNode v : seed.vendors()) {
            if (!vendors.existsById(v.path("gstin").asText())) {
                vendors.save(new Vendor(v.path("gstin").asText(), v.path("legal_name").asText(), v.path("city").asText(),
                        v.path("state_code").asText(), v.path("email").asText(), v.path("contact_person").asText(),
                        v.path("phone").asText(), v.path("supplies").asText()));
            }
        }
    }

    /** A month is reconciled on the 15th of the following month, the day after its GSTR-2B is generated. */
    public static Instant cycleAt(String period) {
        return YearMonth.parse(period).plusMonths(1).atDay(15).atTime(10, 0).atZone(Fmt.IST).toInstant();
    }

    static String next(String period) {
        return YearMonth.parse(period).plusMonths(1).toString();
    }

    // ---------------------------------------------------------------- memory batches (rebuilt from the database)

    List<HistoryMemoryLoader.Batch> batches() {
        List<HistoryMemoryLoader.Batch> out = new ArrayList<>();
        for (String p : SeedCatalog.HISTORY_PERIODS) {
            out.add(batch(p, cycleAt(p), cycleAt(next(p))));
        }
        return out;
    }

    HistoryMemoryLoader.Batch batch(String period, Instant from, Instant to) {
        List<MemoryItem> items = new ArrayList<>();
        List<FileUpload> files = new ArrayList<>();
        List<Mismatch> all = mismatches.findAllByOrderByDetectedAtAscIdAsc();
        for (Mismatch m : all) {
            if (in(m.getDetectedAt(), from, to)) {
                items.add(writer.detected(m));
            }
            if (m.getVerdict() != null && in(m.getVerdictAt(), from, to)) {
                items.add(writer.outcome(m));
            }
        }
        items.addAll(vendorMonths(period, from, all));

        Map<String, List<VendorCommunication>> threads = new LinkedHashMap<>();
        for (VendorCommunication c : communications.findAllByOrderByOccurredAtAsc()) {
            if (in(c.getOccurredAt(), from, to)) {
                threads.computeIfAbsent(c.getVendorGstin(), k -> new ArrayList<>()).add(c);
                if (c.getAttachment() != null && seed.hasLetter(c.getAttachment())) {
                    files.add(writer.letter(c, seed.letter(c.getAttachment())));
                }
            }
            if (c.getPromiseCheckedAt() != null && in(c.getPromiseCheckedAt(), from, to)) {
                items.add(writer.promiseResult(c, c.getPromiseStatus() == VendorCommunication.PromiseStatus.KEPT
                        ? "Confirmed by the GSTR-2B data." : "The GSTR-2B data showed the invoices were still not filed."));
            }
        }
        threads.values().forEach(t -> items.add(mergeThread(t)));
        for (Vendor v : vendors.findAll()) {
            for (AccountantAction a : actions.findByVendorGstinOrderByOccurredAtAsc(v.getGstin())) {
                if (in(a.getOccurredAt(), from, to)) {
                    items.add(writer.action(a, a.getMismatchId() == null ? null : mismatches.findById(a.getMismatchId()).orElse(null)));
                }
            }
        }
        for (Recommendation r : recommendations.findAll()) {
            Mismatch m = mismatches.findById(r.getMismatchId()).orElseThrow();
            String cause = r.getTopCause() == null ? "UNKNOWN" : r.getTopCause().name();
            if (in(r.getCreatedAt(), from, to)) {
                items.add(writer.recommendation(new MemoryWriter.RecommendationEvent(r.getCreatedAt(), m, r.getCategory().name(),
                        cause, r.getHeadline(), r.getNextStep(), r.getRuleId())));
            }
            if (r.getDecidedAt() != null && in(r.getDecidedAt(), from, to)) {
                items.add(writer.decision(new MemoryWriter.DecisionEvent(r.getDecidedAt(), r.getCreatedAt(), m, r.getCategory().name(),
                        cause, r.getDecision().name(), r.getDecisionReason().name(), r.getDecisionNote(), r.getDecidedBy())));
            }
            if (r.getJudgedAt() != null && in(r.getJudgedAt(), from, to)) {
                items.add(writer.judged(new MemoryWriter.JudgedEvent(r.getJudgedAt(), r.getCreatedAt(), m, r.getCategory().name(),
                        r.getPredictedOutcome().name(), r.getActualOutcome().name(), r.getWasCorrect())));
            }
        }
        items.sort(Comparator.comparing(MemoryItem::timestamp));
        return new HistoryMemoryLoader.Batch("History: " + Fmt.month(period), period, items, files);
    }

    private List<MemoryItem> vendorMonths(String period, Instant from, List<Mismatch> all) {
        Map<String, Integer> booked = new LinkedHashMap<>();
        records.findByPeriodAndSourceOrderByIdAsc(period, InvoiceRow.Source.BOOKS).stream()
                .filter(r -> r.getKind() == InvoiceRow.Kind.INVOICE)
                .forEach(r -> booked.merge(r.getSupplierGstin(), 1, Integer::sum));
        List<MemoryItem> out = new ArrayList<>();
        booked.forEach((gstin, n) -> {
            List<Mismatch> own = all.stream().filter(m -> m.getPeriod().equals(period) && m.getVendorGstin().equals(gstin)
                    && m.getBooksRecordId() != null).toList();
            long late = own.stream().filter(m -> m.getType() == MismatchType.MISSING_IN_2B
                    || m.getType() == MismatchType.POSSIBLE_DUPLICATE || m.getType() == MismatchType.GSTIN_MISMATCH).count();
            out.add(writer.vendorMonth(new MemoryWriter.VendorMonth(gstin, period, from, n, (int) (n - late),
                    own.stream().map(m -> m.getType() + " on " + m.invoiceNo()).toList())));
        });
        return out;
    }

    /** Messages of one vendor thread within a batch become one appended chunk of the thread document. */
    private MemoryItem mergeThread(List<VendorCommunication> thread) {
        List<MemoryItem> parts = thread.stream().map(writer::communication).toList();
        MemoryItem first = parts.get(0);
        String text = String.join("\n", parts.stream().map(MemoryItem::content).toList());
        return new MemoryItem(text, first.timestamp(), first.context(), first.documentId(), first.tags(), first.metadata(),
                first.observationScopes(), first.entities(), first.resolveEntities(), first.updateMode());
    }

    private void afterMemory() {
        mentalModels.refreshAll();
        pages.ensureVendorPages(vendors.findAllByOrderByLegalNameAsc());
    }

    private static boolean in(Instant at, Instant from, Instant to) {
        return at != null && !at.isBefore(from) && at.isBefore(to);
    }

    // ---------------------------------------------------------------- summary line

    public Summary summary() {
        List<Mismatch> history = mismatches.findAll().stream().filter(m -> SeedCatalog.HISTORY_PERIODS.contains(m.getPeriod())).toList();
        int invoices = SeedCatalog.HISTORY_PERIODS.stream()
                .mapToInt(p -> (int) records.countByPeriodAndSource(p, InvoiceRow.Source.BOOKS)).sum();
        List<VendorCommunication> comms = communications.findAll();
        int kept = (int) comms.stream().filter(c -> c.getPromiseStatus() == VendorCommunication.PromiseStatus.KEPT).count();
        int broken = (int) comms.stream().filter(c -> c.getPromiseStatus() == VendorCommunication.PromiseStatus.BROKEN).count();
        int recs = (int) recommendations.count();
        BigDecimal open = history.stream().filter(m -> m.getStatus().open()).map(Mismatch::getExposure).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal loss = history.stream().map(Mismatch::getConfirmedLoss).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recovered = history.stream().filter(m -> m.getStatus() == MismatchStatus.RESOLVED)
                .map(Mismatch::getRecoveredAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        int vendorsWithIssues = (int) history.stream().map(Mismatch::getVendorGstin).distinct().count();
        String line = "Apr to Jul 2026: " + invoices + " invoices, " + history.size() + " mismatches across " + vendorsWithIssues
                + " vendors, " + comms.size() + " vendor messages (" + kept + " promises kept, " + broken + " broken), "
                + recs + " past recommendations. " + Fmt.inr(open) + " still open, " + Fmt.inr(recovered) + " resolved, "
                + Fmt.inr(loss) + " confirmed loss.";
        return new Summary(invoices, history.size(), vendorsWithIssues, comms.size(), kept, broken, recs, open, loss, recovered, line);
    }
}

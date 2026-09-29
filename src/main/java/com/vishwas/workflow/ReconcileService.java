package com.vishwas.workflow;

import com.vishwas.advisor.AdviceRun;
import com.vishwas.advisor.AdviceService;
import com.vishwas.advisor.LearningLoop;
import com.vishwas.advisor.VendorProfileService;
import com.vishwas.ingest.Fmt;
import com.vishwas.ingest.IngestService;
import com.vishwas.ingest.InvoiceRow;
import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchRepository;
import com.vishwas.matching.MismatchStatus;
import com.vishwas.memory.MemoryPublisher;
import com.vishwas.memory.MentalModels;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The two live steps of the product:
 * <ul>
 *   <li><b>Reconcile a month</b>: import both files, judge older open cases, match, remember, then advise on every
 *       open case in view (this month's plus carried-forward ones).</li>
 *   <li><b>Next month arrives</b>: import the later GSTR-2B, judge the open cases live, remember the verdicts and
 *       whether Vishwas's recommendations were right, refresh the mental models, and show each vendor's dimension
 *       cards before and after.</li>
 * </ul>
 */
@Service
public class ReconcileService {

    public record FileInput(String filename, byte[] bytes) {
    }

    public record ReconcileResult(String period, IngestService.ImportSummary books, IngestService.ImportSummary gstr2b,
                                  int exactMatches, int newMismatches, int judged, int promisesSettled, Long adviceRunId,
                                  String adviceMode, int memoriesQueued) {
    }

    public record VerdictView(long mismatchId, String vendor, String gstin, String invoice, String type, String period,
                              String outcome, String outcomeLabel, Integer monthsLate, String note,
                              java.math.BigDecimal exposure, java.math.BigDecimal recovered) {
    }

    public record CardChange(String gstin, String vendor, List<VendorProfileService.DimensionCard> before,
                             List<VendorProfileService.DimensionCard> after, List<String> changedDimensions) {
    }

    public record NextMonthResult(String period, List<VerdictView> verdicts, int promisesSettled, int recommendationsJudged,
                                  long recommendationsRight, List<CardChange> vendors, List<LearningLoop.Accuracy> accuracyBefore,
                                  List<LearningLoop.Accuracy> accuracyAfter, int memoriesQueued, boolean memoryOn,
                                  Instant memorySubmittedAt) {
    }

    private final IngestService ingest;
    private final MonthProcessor processor;
    private final MonthMemory monthMemory;
    private final MemoryPublisher publisher;
    private final MentalModels mentalModels;
    private final AdviceService advice;
    private final MismatchRepository mismatches;
    private final VendorProfileService profiles;
    private final LearningLoop learning;
    private final Clock clock;

    public ReconcileService(IngestService ingest, MonthProcessor processor, MonthMemory monthMemory, MemoryPublisher publisher,
                            MentalModels mentalModels, AdviceService advice, MismatchRepository mismatches,
                            VendorProfileService profiles, LearningLoop learning, Clock clock) {
        this.ingest = ingest;
        this.processor = processor;
        this.monthMemory = monthMemory;
        this.publisher = publisher;
        this.mentalModels = mentalModels;
        this.advice = advice;
        this.mismatches = mismatches;
        this.profiles = profiles;
        this.learning = learning;
        this.clock = clock;
    }

    public ReconcileResult reconcile(String period, FileInput books, FileInput gstr2b) {
        var b = ingest.importFile(period, InvoiceRow.Source.BOOKS, books.filename(), books.bytes());
        var g = ingest.importFile(period, InvoiceRow.Source.GSTR2B, gstr2b.filename(), gstr2b.bytes());
        boolean changed = !b.unchanged() || !g.unchanged();
        MonthProcessor.Result r = processor.process(period, Instant.now(clock), MonthProcessor.Mode.RECONCILE, changed);
        var items = monthMemory.items(r);
        publisher.publish("Reconciliation " + Fmt.month(period), period, items);
        List<Mismatch> open = casesInView(period).stream().filter(m -> m.getStatus().open()).toList();
        AdviceRun run = advice.start(period, open);
        if (publisher.enabled() && !items.isEmpty()) {
            Thread.ofVirtual().start(mentalModels::refreshAll);
        }
        return new ReconcileResult(period, b, g, r.exactMatches(), r.detected().size(), r.judged().size(),
                r.promisesSettled().size(), run.getId(), run.getMode(), publisher.enabled() ? items.size() : 0);
    }

    /** Re-run advice for a period without re-importing (e.g. after memory came back). */
    public AdviceRun readvise(String period) {
        return advice.start(period, casesInView(period).stream().filter(m -> m.getStatus().open()).toList());
    }

    public NextMonthResult nextMonth(String period, FileInput gstr2b, FileInput books) {
        Set<String> affected = new LinkedHashSet<>();
        mismatches.findByStatusInOrderByPeriodAscIdAsc(List.of(MismatchStatus.OPEN, MismatchStatus.AT_RISK)).stream()
                .filter(m -> m.getPeriod().compareTo(period) < 0).forEach(m -> affected.add(m.getVendorGstin()));
        Map<String, List<com.vishwas.advisor.VendorProfileService.DimensionCard>> before = new LinkedHashMap<>();
        affected.forEach(g -> before.put(g, profiles.cards(g)));
        List<LearningLoop.Accuracy> accuracyBefore = learning.accuracy();

        ingest.importFile(period, InvoiceRow.Source.GSTR2B, gstr2b.filename(), gstr2b.bytes());
        if (books != null) {
            ingest.importFile(period, InvoiceRow.Source.BOOKS, books.filename(), books.bytes());
        }
        MonthProcessor.Result r = processor.process(period, Instant.now(clock), MonthProcessor.Mode.JUDGE_ONLY, false);
        var items = monthMemory.items(r);
        Instant submitted = Instant.now(clock).minusSeconds(1);
        publisher.publish("Next month: " + Fmt.month(period), period, items);
        if (publisher.enabled()) {
            Thread.ofVirtual().start(mentalModels::refreshAll);
        }

        List<CardChange> changes = new ArrayList<>();
        for (String gstin : affected) {
            var after = profiles.cards(gstin);
            List<String> changed = changedDimensions(before.get(gstin), after);
            if (!changed.isEmpty()) {
                String name = r.judged().stream().filter(m -> m.getVendorGstin().equals(gstin)).map(Mismatch::getVendorName)
                        .findFirst().orElse(gstin);
                changes.add(new CardChange(gstin, name, before.get(gstin), after, changed));
            }
        }
        List<VerdictView> verdicts = r.judged().stream().map(ReconcileService::view).toList();
        long right = r.judgedRecommendations().stream().filter(com.vishwas.memory.MemoryWriter.JudgedEvent::correct).count();
        return new NextMonthResult(period, verdicts, r.promisesSettled().size(), r.judgedRecommendations().size(), right, changes,
                accuracyBefore, learning.accuracy(), publisher.enabled() ? items.size() : 0, publisher.enabled(), submitted);
    }

    static List<String> changedDimensions(List<VendorProfileService.DimensionCard> before, List<VendorProfileService.DimensionCard> after) {
        List<String> changed = new ArrayList<>();
        for (int i = 0; i < after.size(); i++) {
            var a = after.get(i);
            var b = before.get(i);
            if (!Objects.equals(a.headline(), b.headline()) || !Objects.equals(a.outcomes(), b.outcomes())
                    || a.openExposure().compareTo(b.openExposure()) != 0 || !Objects.equals(a.reliability(), b.reliability())) {
                changed.add(a.dimension());
            }
        }
        return changed;
    }

    static VerdictView view(Mismatch m) {
        return new VerdictView(m.getId(), m.getVendorName(), m.getVendorGstin(), m.invoiceNo(), m.getType().name(), m.getPeriod(),
                m.getVerdict().name(), m.getVerdict().label(), m.getMonthsLate(), m.getVerdictNote(), m.getExposure(), m.getRecoveredAmount());
    }

    /** Verdicts reached when this period's GSTR-2B was processed (Step 4 after a reload). */
    public List<VerdictView> verdicts(String period) {
        return mismatches.findByVerdictPeriod(period).stream().filter(m -> m.getVerdict() != null).map(ReconcileService::view).toList();
    }

    /** This period's mismatches plus open ones carried forward from earlier periods. */
    public List<Mismatch> casesInView(String period) {
        List<Mismatch> carried = mismatches.findByStatusInOrderByPeriodAscIdAsc(List.of(MismatchStatus.OPEN, MismatchStatus.AT_RISK))
                .stream().filter(m -> m.getPeriod().compareTo(period) < 0).toList();
        return Stream.concat(mismatches.findByPeriodOrderByVendorNameAscIdAsc(period).stream(), carried.stream()).toList();
    }
}

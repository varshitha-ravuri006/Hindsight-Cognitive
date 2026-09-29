package com.vishwas.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishwas.advisor.LearningLoop;
import com.vishwas.advisor.RecommendationRepository;
import com.vishwas.config.VishwasProperties;
import com.vishwas.ingest.IngestService;
import com.vishwas.ingest.InvoiceRow;
import com.vishwas.ingest.Vendor;
import com.vishwas.ingest.VendorRepository;
import com.vishwas.matching.Finding;
import com.vishwas.matching.MatchResult;
import com.vishwas.matching.Matcher;
import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchRepository;
import com.vishwas.matching.MismatchStatus;
import com.vishwas.matching.MismatchType;
import com.vishwas.memory.MemoryWriter;
import com.vishwas.outcomes.OpenCase;
import com.vishwas.outcomes.OutcomeDetector;
import com.vishwas.outcomes.PromiseTracker;
import com.vishwas.outcomes.VendorCommunication;
import com.vishwas.outcomes.VendorCommunicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Processes one month's GSTR-2B (and purchase register), in one transaction:
 * <ol>
 *   <li>the outcome detector judges every open mismatch from earlier months against the new data;</li>
 *   <li>recommendations on those cases are judged right or wrong (learning loop);</li>
 *   <li>vendor promises that fell due are judged KEPT or BROKEN;</li>
 *   <li>(RECONCILE mode) the matcher reconciles the rows that are left and stores new mismatches.</li>
 * </ol>
 * The result lists everything memory should learn; the caller retains it after the transaction commits.
 */
@Service
public class MonthProcessor {

    private static final Logger log = LoggerFactory.getLogger(MonthProcessor.class);

    public enum Mode { RECONCILE, JUDGE_ONLY }

    public record Result(String period, Mode mode, List<Mismatch> detected, List<Mismatch> judged,
                         List<VendorCommunication> promisesSettled, List<MemoryWriter.JudgedEvent> judgedRecommendations,
                         List<MemoryWriter.VendorMonth> vendorMonths, int exactMatches, int booksRows, int gstr2bRows,
                         boolean matched) {
    }

    private final IngestService ingest;
    private final MismatchRepository mismatches;
    private final RecommendationRepository recommendations;
    private final VendorRepository vendors;
    private final VendorCommunicationRepository communications;
    private final LearningLoop learning;
    private final ObjectMapper json;
    private final Matcher matcher;
    private final OutcomeDetector detector;

    public MonthProcessor(IngestService ingest, MismatchRepository mismatches, RecommendationRepository recommendations,
                          VendorRepository vendors, VendorCommunicationRepository communications, LearningLoop learning,
                          ObjectMapper json, VishwasProperties props) {
        this.ingest = ingest;
        this.mismatches = mismatches;
        this.recommendations = recommendations;
        this.vendors = vendors;
        this.communications = communications;
        this.learning = learning;
        this.json = json;
        var m = props.matching();
        this.matcher = new Matcher(new Matcher.Settings(m.amountToleranceInr(), m.candidateDateWindowDays(), m.candidateMaxEditDistance()));
        this.detector = new OutcomeDetector(new OutcomeDetector.Settings(m.amountToleranceInr(), props.outcomes().atRiskAfterMonths()));
    }

    /**
     * @param rematch in RECONCILE mode, discard this period's unjudged mismatches and match again (after a new upload)
     */
    @Transactional
    public Result process(String period, Instant at, Mode mode, boolean rematch) {
        if (!ingest.imported(period, InvoiceRow.Source.GSTR2B)) {
            throw new IllegalStateException("No GSTR-2B imported for " + period);
        }
        List<InvoiceRow> g2b = ingest.rows(period, InvoiceRow.Source.GSTR2B);
        List<InvoiceRow> books = ingest.rows(period, InvoiceRow.Source.BOOKS);

        // 1. judge open cases from earlier months
        List<Mismatch> open = mismatches.findByStatusInOrderByPeriodAscIdAsc(List.of(MismatchStatus.OPEN, MismatchStatus.AT_RISK))
                .stream().filter(m -> m.getPeriod().compareTo(period) < 0).toList();
        Map<Long, Mismatch> openById = new HashMap<>();
        open.forEach(m -> openById.put(m.getId(), m));
        var judgement = detector.judge(open.stream().map(OpenCase::of).toList(), g2b, books, period);
        List<Mismatch> judged = new ArrayList<>();
        for (var v : judgement.verdicts()) {
            Mismatch m = openById.get(v.caseId());
            m.judge(v.outcome(), period, at, v.monthsLate(), v.recovered(), v.explanation(), v.evidenceRowId());
            judged.add(m);
        }

        // 2. learning loop
        List<MemoryWriter.JudgedEvent> judgedRecs = learning.judge(judged, at);

        // 3. promises that fell due
        LocalDate generated = YearMonth.parse(period).plusMonths(1).atDay(14);
        List<VendorCommunication> settled = new ArrayList<>();
        for (VendorCommunication c : communications.findByPromiseStatus(VendorCommunication.PromiseStatus.PENDING)) {
            Map<String, Boolean> resolved = new HashMap<>();
            for (String invoice : c.invoices()) {
                resolved.put(invoice, isResolved(c.getVendorGstin(), invoice));
            }
            PromiseTracker.judge(c.getPromiseBy(), c.invoices(), generated, resolved).ifPresent(status -> {
                c.settlePromise(status, at);
                settled.add(c);
            });
        }

        // 4. reconcile what is left
        List<Mismatch> detected = new ArrayList<>();
        List<MemoryWriter.VendorMonth> months = new ArrayList<>();
        int exact = 0;
        boolean matched = false;
        if (mode == Mode.RECONCILE) {
            List<Mismatch> existing = mismatches.findByPeriodOrderByVendorNameAscIdAsc(period);
            if (rematch && !existing.isEmpty()) {
                List<Mismatch> removable = existing.stream().filter(m -> m.getVerdict() == null).toList();
                removable.forEach(m -> recommendations.deleteAll(recommendations.findByMismatchIdOrderByCreatedAtAsc(m.getId())));
                mismatches.deleteAll(removable);
                mismatches.flush();
                existing = existing.stream().filter(m -> m.getVerdict() != null).toList();
            }
            if (existing.isEmpty()) {
                Set<Long> consumed = new HashSet<>(judgement.consumedRowIds());
                consumed.addAll(mismatches.findEvidenceRecordIds());
                MatchResult result = matcher.match(books.stream().filter(r -> !consumed.contains(r.id())).toList(),
                        g2b.stream().filter(r -> !consumed.contains(r.id())).toList());
                exact = result.exactMatches();
                detected = store(period, result.findings(), at);
                months = vendorMonths(period, books, result.findings(), at);
                matched = true;
            }
        }
        log.info("Processed {} ({}): {} judged, {} new mismatches, {} promises settled", period, mode, judged.size(),
                detected.size(), settled.size());
        return new Result(period, mode, detected, judged, settled, judgedRecs, months, exact, books.size(), g2b.size(), matched);
    }

    private boolean isResolved(String gstin, String invoice) {
        List<Mismatch> cases = mismatches.findByVendorGstinOrderByDetectedAtAscIdAsc(gstin).stream()
                .filter(m -> invoice.equalsIgnoreCase(m.invoiceNo())).toList();
        return cases.isEmpty() || cases.stream().allMatch(m -> m.getStatus() == MismatchStatus.RESOLVED);
    }

    private List<Mismatch> store(String period, List<Finding> findings, Instant at) {
        List<Mismatch> saved = new ArrayList<>();
        Map<Long, Long> mismatchOfBooksRow = new HashMap<>();
        for (Finding f : findings) {
            String gstin = f.books() != null ? f.books().gstin() : f.gstr2b().gstin();
            String name = vendors.findById(gstin).map(Vendor::getLegalName).orElse(f.primary().supplierName());
            Mismatch m = mismatches.save(Mismatch.detected(period, f, name, at, write(f.differences()), write(f.candidates())));
            if (f.type() == MismatchType.MISSING_IN_2B && f.books() != null) {
                mismatchOfBooksRow.put(f.books().id(), m.getId());
            }
            saved.add(m);
        }
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            if (f.type() == MismatchType.MISSING_IN_BOOKS && f.linkedRowId() != null) {
                saved.get(i).linkTo(mismatchOfBooksRow.get(f.linkedRowId()));
            }
        }
        return saved;
    }

    /** Per vendor: invoices booked this month and how many reached GSTR-2B on time (evidence of reliability). */
    private List<MemoryWriter.VendorMonth> vendorMonths(String period, List<InvoiceRow> books, List<Finding> findings, Instant at) {
        Map<String, Integer> booked = new LinkedHashMap<>();
        books.stream().filter(r -> r.kind() == InvoiceRow.Kind.INVOICE).forEach(r -> booked.merge(r.gstin(), 1, Integer::sum));
        List<MemoryWriter.VendorMonth> out = new ArrayList<>();
        for (var e : booked.entrySet()) {
            List<Finding> own = findings.stream().filter(f -> f.books() != null && f.books().gstin().equals(e.getKey())).toList();
            long late = own.stream().filter(f -> f.type() == MismatchType.MISSING_IN_2B || f.type() == MismatchType.POSSIBLE_DUPLICATE
                    || f.type() == MismatchType.GSTIN_MISMATCH).count();
            List<String> labels = own.stream().map(f -> f.type() + " on " + f.books().invoiceNo()).toList();
            out.add(new MemoryWriter.VendorMonth(e.getKey(), period, at, e.getValue(), (int) (e.getValue() - late), labels));
        }
        return out;
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}

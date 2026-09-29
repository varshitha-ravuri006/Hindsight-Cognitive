package com.vishwas.workflow;

import com.vishwas.advisor.AdviceRun;
import com.vishwas.advisor.AdviceRunRepository;
import com.vishwas.advisor.AdviceService;
import com.vishwas.advisor.Category;
import com.vishwas.advisor.Cause;
import com.vishwas.advisor.Recommendation;
import com.vishwas.ingest.Fmt;
import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchRepository;
import com.vishwas.outcomes.MoneyCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Money at risk": the three numbers, the attention count, and the case list grouped by vendor, each case with
 * its category badge, one-line explanation and the no-memory baseline beside it.
 */
@Service
@Transactional(readOnly = true)
public class WorkspaceService {

    public record Money(BigDecimal needsReview, BigDecimal likelyTiming, BigDecimal confirmedLossToDate,
                        BigDecimal recoveredToDate, BigDecimal potentialExposure, int attentionCases, int openCases,
                        int totalMismatches) {
    }

    public record CaseRow(long id, String period, String periodLabel, boolean carriedForward, String vendorGstin, String vendor,
                          String invoice, String type, String typeLabel, String dimension, String status, String verdict,
                          BigDecimal exposure, Long recommendationId, String category, String categoryLabel, String source,
                          String headline, String nextStep, String topCause, String confidence, String confidenceText,
                          Integer historyCases, String baselineAction, String baselineCategory, String decision,
                          Long linkedMismatchId, boolean hasCandidates, String rule) {
    }

    public record VendorGroup(String gstin, String name, BigDecimal exposure, BigDecimal needsReview, int cases,
                              String strictestCategory, List<CaseRow> rows) {
    }

    public record AdviceStatus(Long runId, String status, String mode, int vendorsDone, int vendorsTotal, String message,
                               Long durationMs) {
        static AdviceStatus of(AdviceRun r) {
            return r == null ? null : new AdviceStatus(r.getId(), r.getStatus(), r.getMode(), r.getVendorsDone(), r.getVendorsTotal(),
                    r.getMessage(), r.getDurationMs());
        }
    }

    public record Workspace(String period, String periodLabel, Money money, List<VendorGroup> vendors, AdviceStatus advice,
                            long booksRows, long gstr2bRows, long newMismatches) {
    }

    private final ReconcileService reconcile;
    private final AdviceService advice;
    private final AdviceRunRepository runs;
    private final MismatchRepository mismatches;
    private final com.vishwas.ingest.InvoiceRecordRepository records;

    public WorkspaceService(ReconcileService reconcile, AdviceService advice, AdviceRunRepository runs, MismatchRepository mismatches,
                            com.vishwas.ingest.InvoiceRecordRepository records) {
        this.reconcile = reconcile;
        this.advice = advice;
        this.runs = runs;
        this.mismatches = mismatches;
        this.records = records;
    }

    public Workspace workspace(String period) {
        List<Mismatch> cases = reconcile.casesInView(period);
        Map<Long, Recommendation> recs = advice.latest(cases);
        List<CaseRow> rows = cases.stream().map(m -> row(m, recs.get(m.getId()), period)).toList();

        List<MoneyCalculator.Line> lines = new ArrayList<>();
        for (CaseRow r : rows) {
            Mismatch m = cases.stream().filter(c -> c.getId() == r.id()).findFirst().orElseThrow();
            Category c = r.category() == null ? null : Category.valueOf(r.category());
            boolean review = c != null && c.needsReview();
            boolean timing = c == Category.RECOMMEND && Cause.TIMING_DIFFERENCE.name().equals(r.topCause());
            boolean attention = m.getStatus().open() && c != Category.AUTO_RESOLVE;
            lines.add(new MoneyCalculator.Line(m.getExposure(), m.getStatus(), review, timing, attention,
                    m.getRecoveredAmount(), m.getConfirmedLoss()));
        }
        MoneyCalculator.Summary view = MoneyCalculator.summarise(lines);
        MoneyCalculator.Summary allTime = MoneyCalculator.summarise(mismatches.findAll().stream()
                .map(m -> new MoneyCalculator.Line(m.getExposure(), m.getStatus(), false, false, false, m.getRecoveredAmount(),
                        m.getConfirmedLoss())).toList());
        Money money = new Money(view.needsReview(), view.likelyTiming(), allTime.confirmedLoss(), allTime.recovered(),
                view.potentialExposure(), view.attentionCases(), view.openCases(), rows.size());

        Map<String, List<CaseRow>> byVendor = new LinkedHashMap<>();
        rows.forEach(r -> byVendor.computeIfAbsent(r.vendorGstin(), k -> new ArrayList<>()).add(r));
        List<VendorGroup> groups = new ArrayList<>();
        byVendor.forEach((gstin, list) -> {
            List<CaseRow> sorted = list.stream().sorted(Comparator.comparing(CaseRow::carriedForward)
                    .thenComparing(CaseRow::exposure, Comparator.reverseOrder())).toList();
            BigDecimal exposure = list.stream().filter(r -> !"RESOLVED".equals(r.status()) && !"WRITTEN_OFF".equals(r.status()))
                    .map(CaseRow::exposure).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal review = list.stream().filter(r -> r.category() != null && Category.valueOf(r.category()).needsReview())
                    .map(CaseRow::exposure).reduce(BigDecimal.ZERO, BigDecimal::add);
            String strictest = list.stream().map(CaseRow::category).filter(java.util.Objects::nonNull)
                    .map(Category::valueOf).reduce(Category.AUTO_RESOLVE, Category::stricter).name();
            groups.add(new VendorGroup(gstin, list.get(0).vendor(), exposure, review, list.size(), strictest, sorted));
        });
        groups.sort(Comparator.comparing((VendorGroup g) -> Category.valueOf(g.strictestCategory()).ordinal()).reversed()
                .thenComparing(VendorGroup::exposure, Comparator.reverseOrder()));
        AdviceRun run = runs.findFirstByPeriodOrderByStartedAtDesc(period).orElse(null);
        return new Workspace(period, Fmt.month(period), money, groups, AdviceStatus.of(run),
                records.countByPeriodAndSource(period, com.vishwas.ingest.InvoiceRow.Source.BOOKS),
                records.countByPeriodAndSource(period, com.vishwas.ingest.InvoiceRow.Source.GSTR2B),
                mismatches.countByPeriod(period));
    }

    public AdviceStatus adviceStatus(long runId) {
        return AdviceStatus.of(runs.findById(runId).orElse(null));
    }

    static CaseRow row(Mismatch m, Recommendation r, String period) {
        return new CaseRow(m.getId(), m.getPeriod(), Fmt.month(m.getPeriod()), m.getPeriod().compareTo(period) < 0,
                m.getVendorGstin(), m.getVendorName(), m.invoiceNo(), m.getType().name(), m.getType().label(),
                m.getDimension().name(), m.getStatus().name(), m.getVerdict() == null ? null : m.getVerdict().name(),
                m.getExposure(),
                r == null ? null : r.getId(),
                r == null ? null : r.getCategory().name(),
                r == null ? null : r.getCategory().label(),
                r == null ? null : r.getSource().name(),
                r == null ? null : r.getHeadline(),
                r == null ? null : r.getNextStep(),
                r == null || r.getTopCause() == null ? null : r.getTopCause().name(),
                r == null ? null : r.getConfidenceLevel(),
                r == null ? null : r.getConfidenceText(),
                r == null ? null : r.getHistoryCases(),
                r == null ? null : r.getBaselineAction(),
                r == null ? null : r.getBaselineCategory(),
                r == null || r.getDecision() == null ? null : r.getDecision().name(),
                m.getLinkedMismatchId(), m.getCandidatesJson() != null && m.getCandidatesJson().length() > 2,
                r == null ? null : r.getRuleId());
    }
}

package com.vishwas.advisor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishwas.config.VishwasProperties;
import com.vishwas.ingest.Vendor;
import com.vishwas.ingest.VendorRepository;
import com.vishwas.matching.Mismatch;
import com.vishwas.memory.MemoryPublisher;
import com.vishwas.memory.MemoryWriter;
import com.vishwas.memory.hindsight.MemoryItem;
import com.vishwas.memory.hindsight.RecallHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Produces recommendations for a period's open cases:
 * <ol>
 *   <li>one reflect per vendor with open cases, all in parallel (bounded), each scoped to that vendor;</li>
 *   <li>the no-memory baseline for the same cases, in parallel with the above;</li>
 *   <li>per case: deterministic guardrails, database confidence and database evidence;</li>
 *   <li>every recommendation is stored and remembered (so Vishwas can learn from its own track record).</li>
 * </ol>
 * Without memory, every case gets the textbook action. A failing vendor never fails the run.
 */
@Service
public class AdviceService {

    private static final Logger log = LoggerFactory.getLogger(AdviceService.class);

    private final MemoryAdvisor memoryAdvisor;
    private final BaselineAdvisor baseline;
    private final VendorHistoryService history;
    private final RecommendationRepository recommendations;
    private final AdviceRunRepository runs;
    private final VendorRepository vendors;
    private final MemoryWriter writer;
    private final MemoryPublisher publisher;
    private final ExecutorService io;
    private final VishwasProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final CategoryPolicy policy;

    public AdviceService(MemoryAdvisor memoryAdvisor, BaselineAdvisor baseline, VendorHistoryService history,
                         RecommendationRepository recommendations, AdviceRunRepository runs, VendorRepository vendors, MemoryWriter writer, MemoryPublisher publisher, ExecutorService ioExecutor,
                         VishwasProperties props, ObjectMapper json, Clock clock) {
        this.memoryAdvisor = memoryAdvisor;
        this.baseline = baseline;
        this.history = history;
        this.recommendations = recommendations;
        this.runs = runs;
        this.vendors = vendors;
        this.writer = writer;
        this.publisher = publisher;
        this.io = ioExecutor;
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.policy = new CategoryPolicy(new CategoryPolicy.Settings(props.advice().materialityInr(),
                props.advice().thinHistoryCases(), props.autoResolve().approved(CategoryPolicy.FORMAT_ONLY)));
    }

    /** Creates a run and advises in the background; poll {@link AdviceRunRepository} for progress. */
    public AdviceRun start(String period, List<Mismatch> cases) {
        boolean memory = publisher.enabled();
        Map<String, List<Mismatch>> byVendor = cases.stream()
                .collect(Collectors.groupingBy(Mismatch::getVendorGstin, LinkedHashMap::new, Collectors.toList()));
        AdviceRun run = runs.save(new AdviceRun(period, Instant.now(clock), memory ? "MEMORY" : "TEXTBOOK", byVendor.size()));
        Thread.ofVirtual().name("advice-" + period).start(() -> execute(run.getId(), period, byVendor, memory));
        return run;
    }

    /** Synchronous variant (used by tests and the snapshot recorder). */
    public AdviceRun runNow(String period, List<Mismatch> cases) {
        AdviceRun run = start(period, cases);
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            AdviceRun r = runs.findById(run.getId()).orElseThrow();
            if (!AdviceRun.RUNNING.equals(r.getStatus())) {
                return r;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return runs.findById(run.getId()).orElseThrow();
    }

    void execute(long runId, String period, Map<String, List<Mismatch>> byVendor, boolean memory) {
        List<Mismatch> all = byVendor.values().stream().flatMap(List::stream).toList();
        CompletableFuture<Map<Long, BaselineAdvisor.BaselineAdvice>> baselineFuture =
                CompletableFuture.supplyAsync(() -> baseline.advise(period, all), io);
        Semaphore permits = new Semaphore(Math.max(1, props.advice().parallelism()));
        AtomicInteger done = new AtomicInteger();
        List<String> problems = Collections.synchronizedList(new ArrayList<>());
        List<MemoryItem> remembered = Collections.synchronizedList(new ArrayList<>());

        List<CompletableFuture<Void>> vendorsWork = new ArrayList<>();
        for (var entry : byVendor.entrySet()) {
            vendorsWork.add(CompletableFuture.runAsync(() -> {
                String gstin = entry.getKey();
                String name = vendors.findById(gstin).map(Vendor::getLegalName).orElse(entry.getValue().get(0).getVendorName());
                MemoryAdvisor.VendorAdvice advice = null;
                if (memory) {
                    try {
                        permits.acquire();
                        try {
                            advice = memoryAdvisor.advise(gstin, name, period, entry.getValue());
                        } finally {
                            permits.release();
                        }
                        if (advice.error() != null) {
                            problems.add(name + ": " + advice.error());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException e) {
                        log.warn("Memory advice failed for {}: {}", name, e.getMessage());
                        problems.add(name + ": memory unavailable (" + e.getMessage() + "), textbook action shown");
                        advice = MemoryAdvisor.VendorAdvice.failed(gstin, e.getMessage());
                    }
                }
                for (Mismatch m : entry.getValue()) {
                    Recommendation r = recommend(runId, m, advice);
                    if (r.getSource() == Recommendation.Source.MEMORY) {
                        remembered.add(writer.recommendation(new MemoryWriter.RecommendationEvent(r.getCreatedAt(), m,
                                r.getCategory().name(), r.getTopCause() == null ? "UNKNOWN" : r.getTopCause().name(),
                                r.getHeadline(), r.getNextStep(), r.getRuleId())));
                    }
                }
                progress(runId, done.incrementAndGet());
            }, io));
        }

        try {
            CompletableFuture.allOf(vendorsWork.toArray(CompletableFuture[]::new)).get(150, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Advice did not finish for every vendor", e);
            problems.add("Advice timed out for some vendors");
        }
        try {
            Map<Long, BaselineAdvisor.BaselineAdvice> base = baselineFuture.get(90, TimeUnit.SECONDS);
            for (Recommendation r : recommendations.findByRunId(runId)) {
                BaselineAdvisor.BaselineAdvice b = base.get(r.getMismatchId());
                if (b != null) {
                    r.baseline(b.action(), b.category().name() + ("TEXTBOOK".equals(b.source()) ? " (textbook)" : ""));
                    recommendations.save(r);
                }
            }
        } catch (Exception e) {
            log.warn("Baseline could not be attached", e);
            problems.add("Baseline unavailable (" + e.getClass().getSimpleName() + ")");
        }
        publisher.publish("Recommendations " + period, period, remembered);
        AdviceRun run = runs.findById(runId).orElseThrow();
        run.finish(AdviceRun.DONE, Instant.now(clock), problems.isEmpty() ? null : String.join(" | ", problems));
        runs.save(run);
        log.info("Advice for {} done in {} ms ({} vendors, mode {}, {} problems)", period, run.getDurationMs(),
                byVendor.size(), run.getMode(), problems.size());
    }

    /** One case: memory proposal (if any) -> guardrails -> confidence -> stored recommendation. */
    Recommendation recommend(long runId, Mismatch m, MemoryAdvisor.VendorAdvice advice) {
        HistoryStats stats = history.historyFor(m);
        Confidence confidence = Confidence.of(stats, props.advice().thinHistoryCases());
        MemoryAdvisor.CaseProposal proposal = advice == null ? null : advice.proposals().get(m.getId());
        boolean eligible = CategoryPolicy.formatOnlyEligible(m.getType(), m.getInvoiceDateBooks(), m.getInvoiceDateGstr2b(),
                m.getTaxableBooks(), m.getTaxableGstr2b(), m.getItcBooks(), m.getItcGstr2b(), props.matching().amountToleranceInr());
        CategoryPolicy.Decision decision = policy.decide(new CategoryPolicy.Input(m.getType(), m.getExposure(), eligible,
                proposal == null ? null : new CategoryPolicy.Proposal(proposal.category(), proposal.topCause(), proposal.nextStep()),
                stats));

        Recommendation r = new Recommendation(m.getId(), runId, m.getVendorGstin(), m.getDimension(), decision.category(),
                proposal == null ? Recommendation.Source.TEXTBOOK : Recommendation.Source.MEMORY, Instant.now(clock));
        if (proposal == null) {
            Cause assumed = TextbookRules.assumedCause(m.getType());
            r.describe(TextbookRules.action(m.getType()), TextbookRules.action(m.getType()), assumed,
                    toJson(List.of(new MemoryAdvisor.Hypothesis(assumed, "UNKNOWN", "Textbook rule for " + m.getType()
                            + "; no vendor history consulted."))), "[]", "[]", toJson(decision.guardrails()), null, decision.rule());
        } else {
            String nextStep = CategoryPolicy.mentionsPaymentAction(proposal.nextStep())
                    ? "Review the invoice, contract and payment status before deciding." : proposal.nextStep();
            String headline = proposal.headline() != null ? proposal.headline()
                    : proposal.hypotheses().isEmpty() ? null : proposal.hypotheses().get(0).evidence();
            r.describe(headline, nextStep, proposal.topCause(), toJson(proposal.hypotheses()), toJson(proposal.evidenceRefs()),
                    toJson(facts(advice.facts())), toJson(decision.guardrails()), advice.vendorSummary(), decision.rule());
        }
        r.confidence(confidence, proposal == null ? null : proposal.confidence());
        return recommendations.save(r);
    }

    private void progress(long runId, int done) {
        runs.findById(runId).ifPresent(r -> {
            r.progress(done);
            runs.save(r);
        });
    }

    private static List<Map<String, Object>> facts(List<RecallHit> hits) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RecallHit h : hits) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", h.id());
            f.put("text", h.text());
            f.put("type", h.type());
            f.put("context", h.context());
            f.put("occurred", h.occurredStart() != null ? h.occurredStart() : h.mentionedAt());
            f.put("documentId", h.documentId());
            out.add(f);
            if (out.size() >= 25) {
                break;
            }
        }
        return out;
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /** Current (latest) recommendation per mismatch. */
    public Map<Long, Recommendation> latest(List<Mismatch> cases) {
        Map<Long, Recommendation> out = new LinkedHashMap<>();
        if (cases.isEmpty()) {
            return out;
        }
        for (Recommendation r : recommendations.findByMismatchIdInOrderByCreatedAtAsc(cases.stream().map(Mismatch::getId).toList())) {
            out.put(r.getMismatchId(), r);
        }
        return out;
    }
}

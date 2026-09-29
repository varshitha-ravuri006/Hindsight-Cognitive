package com.vishwas.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishwas.advisor.Confidence;
import com.vishwas.advisor.HistoryStats;
import com.vishwas.advisor.Recommendation;
import com.vishwas.advisor.RecommendationRepository;
import com.vishwas.advisor.VendorHistoryService;
import com.vishwas.ingest.InvoiceNumbers;
import com.vishwas.ingest.InvoiceRecord;
import com.vishwas.ingest.InvoiceRecordRepository;
import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchRepository;
import com.vishwas.outcomes.VendorCommunication;
import com.vishwas.outcomes.VendorCommunicationRepository;
import com.vishwas.config.VishwasProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The investigation brief for one case: original vs normalised values side by side with the differences,
 * fuzzy candidates, ranked cause hypotheses with their evidence, the exact past cases behind them (from the
 * database), the memory facts the recommendation used, confidence, the suggested next step and the baseline.
 */
@Service
@Transactional(readOnly = true)
public class CaseBriefService {

    public record Values(Long recordId, String invoiceNo, String normalisedNo, LocalDate invoiceDate, BigDecimal taxable,
                         BigDecimal igst, BigDecimal cgst, BigDecimal sgst, BigDecimal itc, String gstin, String description,
                         String voucherNo, LocalDate bookingDate, LocalDate filedOn, String filingPeriod) {
        static Values of(InvoiceRecord r) {
            if (r == null) {
                return null;
            }
            return new Values(r.getId(), r.getInvoiceNo(), InvoiceNumbers.normalise(r.getInvoiceNo()), r.getInvoiceDate(),
                    r.getTaxableValue(), r.getIgst(), r.getCgst(), r.getSgst(), r.toRow().itc(), r.getSupplierGstin(),
                    r.getDescription(), r.getVoucherNo(), r.getBookingDate(), r.getFiledOn(), r.getFilingPeriod());
        }
    }

    public record Communication(Instant at, String direction, String channel, String author, String summary, String promiseBy,
                                String promiseStatus, String attachment) {
    }

    public record PastRecommendation(long id, Instant at, String category, String topCause, String headline, String decision,
                                     String reason, Boolean correct, String actualOutcome) {
    }

    public record Brief(WorkspaceService.CaseRow row, Values books, Values gstr2b, List<Map<String, Object>> differences,
                        List<Map<String, Object>> candidates, List<Map<String, Object>> hypotheses, List<String> evidenceRefs,
                        List<Map<String, Object>> memoryFacts, List<String> guardrails, String vendorSummary,
                        String dimensionLabel, String confidenceLevel, String confidenceText, int historyCases,
                        List<HistoryStats.PastCase> pastCases, List<Communication> communications,
                        List<PastRecommendation> recommendations, String verdictNote, String note) {
    }

    private final MismatchRepository mismatches;
    private final InvoiceRecordRepository records;
    private final RecommendationRepository recommendations;
    private final VendorHistoryService history;
    private final VendorCommunicationRepository communications;
    private final ObjectMapper json;
    private final int thin;

    public CaseBriefService(MismatchRepository mismatches, InvoiceRecordRepository records, RecommendationRepository recommendations,
                            VendorHistoryService history, VendorCommunicationRepository communications, ObjectMapper json,
                            VishwasProperties props) {
        this.mismatches = mismatches;
        this.records = records;
        this.recommendations = recommendations;
        this.history = history;
        this.communications = communications;
        this.json = json;
        this.thin = props.advice().thinHistoryCases();
    }

    public Brief brief(long id, String viewPeriod) {
        Mismatch m = mismatches.findById(id).orElseThrow(() -> new NoSuchElementException("No case " + id));
        Recommendation r = recommendations.findFirstByMismatchIdOrderByCreatedAtDescIdDesc(id).orElse(null);
        HistoryStats stats = history.historyFor(m);
        Confidence confidence = Confidence.of(stats, thin);
        List<Communication> comms = communications.findByVendorGstinOrderByOccurredAtAsc(m.getVendorGstin()).stream()
                .map(CaseBriefService::communication).toList();
        List<PastRecommendation> recs = recommendations.findByVendorGstinOrderByCreatedAtAsc(m.getVendorGstin()).stream()
                .filter(x -> x.getDimension() == m.getDimension())
                .map(x -> new PastRecommendation(x.getId(), x.getCreatedAt(), x.getCategory().name(),
                        x.getTopCause() == null ? null : x.getTopCause().name(), x.getHeadline(),
                        x.getDecision() == null ? null : x.getDecision().name(),
                        x.getDecisionReason() == null ? null : x.getDecisionReason().name(), x.getWasCorrect(),
                        x.getActualOutcome() == null ? null : x.getActualOutcome().name()))
                .toList();
        return new Brief(WorkspaceService.row(m, r, viewPeriod == null ? m.getPeriod() : viewPeriod),
                Values.of(m.getBooksRecordId() == null ? null : records.findById(m.getBooksRecordId()).orElse(null)),
                Values.of(m.getGstr2bRecordId() == null ? null : records.findById(m.getGstr2bRecordId()).orElse(null)),
                list(m.getDifferencesJson()), list(m.getCandidatesJson()),
                r == null ? List.of() : list(r.getCausesJson()),
                r == null ? List.of() : strings(r.getEvidenceRefsJson()),
                r == null ? List.of() : list(r.getMemoryFactsJson()),
                r == null ? List.of() : strings(r.getGuardrailsJson()),
                r == null ? null : r.getVendorSummary(),
                m.getDimension().label(), confidence.level().name(), confidence.explanation(), confidence.cases(),
                stats.chronological(), comms, recs, m.getVerdictNote(), m.getNote());
    }

    static Communication communication(VendorCommunication c) {
        return new Communication(c.getOccurredAt(), c.getDirection().name(), c.getChannel().name(), c.getAuthor(), c.getSummary(),
                c.getPromiseBy() == null ? null : c.getPromiseBy().toString(),
                c.getPromiseStatus() == null ? null : c.getPromiseStatus().name(), c.getAttachment());
    }

    private List<Map<String, Object>> list(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> strings(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}

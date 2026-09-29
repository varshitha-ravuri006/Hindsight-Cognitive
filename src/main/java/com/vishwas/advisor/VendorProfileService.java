package com.vishwas.advisor;

import com.vishwas.config.VishwasProperties;
import com.vishwas.ingest.Fmt;
import com.vishwas.ingest.Vendor;
import com.vishwas.ingest.VendorRepository;
import com.vishwas.matching.Dimension;
import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchRepository;
import com.vishwas.matching.MismatchStatus;
import com.vishwas.matching.Outcome;
import com.vishwas.outcomes.AccountantAction;
import com.vishwas.outcomes.AccountantActionRepository;
import com.vishwas.outcomes.VendorCommunication;
import com.vishwas.outcomes.VendorCommunicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Vendor intelligence, dimension by dimension, never one trust score. Every number here comes from the
 * database (cases, outcomes, days to resolve, exposure, confirmed loss, recovered, reliability of the
 * history); what Hindsight believes is shown next to it, not mixed into it.
 */
@Service
@Transactional(readOnly = true)
public class VendorProfileService {

    public record DimensionCard(String dimension, String label, String question, int cases, int open,
                                Map<String, Long> outcomes, String headline, Double averageDaysToResolve,
                                Double averageMonthsLate, BigDecimal openExposure, BigDecimal confirmedLoss,
                                BigDecimal recovered, String reliability, String reliabilityText,
                                List<HistoryStats.PastCase> pastCases, VendorHistoryService.Responsiveness responsiveness) {
    }

    public record TimelineEvent(Instant at, String kind, String title, String detail, String dimension, BigDecimal amount,
                                Long mismatchId, String tone) {
    }

    public record TrackRecord(long recommendations, long judged, long correct, long accepted, long modified, long rejected) {
    }

    public record Profile(String gstin, String legalName, String city, String stateCode, String email, String contact,
                          String supplies, List<DimensionCard> dimensions, TrackRecord trackRecord, BigDecimal openExposure,
                          BigDecimal confirmedLoss, BigDecimal recovered, List<TimelineEvent> timeline) {
    }

    public record VendorSummary(String gstin, String legalName, String city, int openCases, BigDecimal openExposure,
                                List<String> dimensionsWithIssues, int totalCases) {
    }

    private final VendorRepository vendors;
    private final MismatchRepository mismatches;
    private final VendorCommunicationRepository communications;
    private final AccountantActionRepository actions;
    private final RecommendationRepository recommendations;
    private final VendorHistoryService history;
    private final int thin;

    public VendorProfileService(VendorRepository vendors, MismatchRepository mismatches, VendorCommunicationRepository communications,
                                AccountantActionRepository actions, RecommendationRepository recommendations,
                                VendorHistoryService history, VishwasProperties props) {
        this.vendors = vendors;
        this.mismatches = mismatches;
        this.communications = communications;
        this.actions = actions;
        this.recommendations = recommendations;
        this.history = history;
        this.thin = props.advice().thinHistoryCases();
    }

    public List<VendorSummary> list() {
        List<VendorSummary> out = new ArrayList<>();
        for (Vendor v : vendors.findAllByOrderByLegalNameAsc()) {
            List<Mismatch> cases = mismatches.findByVendorGstinOrderByDetectedAtAscIdAsc(v.getGstin());
            List<Mismatch> open = cases.stream().filter(m -> m.getStatus().open()).toList();
            List<String> dims = cases.stream().map(m -> m.getDimension().name()).distinct().toList();
            out.add(new VendorSummary(v.getGstin(), v.getLegalName(), v.getCity(), open.size(),
                    open.stream().map(Mismatch::getExposure).reduce(BigDecimal.ZERO, BigDecimal::add), dims, cases.size()));
        }
        return out;
    }

    public Profile profile(String gstin) {
        Vendor v = vendors.findById(gstin).orElseThrow(() -> new NoSuchElementException("Unknown vendor " + gstin));
        List<DimensionCard> cards = cards(gstin);
        List<Mismatch> cases = mismatches.findByVendorGstinOrderByDetectedAtAscIdAsc(gstin);
        BigDecimal open = cases.stream().filter(m -> m.getStatus().open()).map(Mismatch::getExposure).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal loss = cases.stream().map(Mismatch::getConfirmedLoss).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recovered = cases.stream().filter(m -> m.getStatus() == MismatchStatus.RESOLVED)
                .map(Mismatch::getRecoveredAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Profile(v.getGstin(), v.getLegalName(), v.getCity(), v.getStateCode(), v.getEmail(), v.getContactPerson(),
                v.getSupplies(), cards, trackRecord(gstin), open, loss, recovered, timeline(gstin, cases));
    }

    /** The six dimension cards (also used for the before/after highlight when a new GSTR-2B arrives). */
    public List<DimensionCard> cards(String gstin) {
        List<DimensionCard> cards = new ArrayList<>();
        for (Dimension d : Dimension.values()) {
            if (d == Dimension.RESPONSIVENESS) {
                cards.add(responsivenessCard(gstin));
                continue;
            }
            HistoryStats stats = history.all(gstin, d);
            Confidence c = Confidence.of(stats, thin);
            Map<String, Long> outcomes = new LinkedHashMap<>();
            stats.outcomeCounts().forEach((o, n) -> outcomes.put(o.name(), n));
            int open = (int) stats.cases().stream().filter(p -> p.status().open() && p.outcome() == null).count();
            cards.add(new DimensionCard(d.name(), d.label(), d.question(), stats.cases().size(), open, outcomes,
                    headline(stats, open), stats.averageDaysToResolve(), stats.averageMonthsLate(), stats.openExposure(),
                    stats.confirmedLoss(), stats.recovered(), c.level().name(), c.explanation(), stats.chronological(), null));
        }
        return cards;
    }

    static String headline(HistoryStats stats, int open) {
        if (stats.cases().isEmpty()) {
            return "No issues on record";
        }
        int judged = stats.judgedCount();
        StringBuilder s = new StringBuilder();
        if (judged > 0) {
            Outcome dominant = stats.dominantOutcome().orElseThrow();
            s.append(stats.count(dominant)).append(" of ").append(judged).append(' ').append(dominant.label().toLowerCase());
            if (dominant == Outcome.RESOLVED_LATE && stats.averageMonthsLate() != null) {
                s.append(" (avg ").append(String.format("%.1f", stats.averageMonthsLate())).append(" mo)");
            }
        }
        if (open > 0) {
            s.append(s.length() > 0 ? " · " : "").append(open).append(" open");
        }
        return s.toString();
    }

    private DimensionCard responsivenessCard(String gstin) {
        VendorHistoryService.Responsiveness r = history.responsiveness(gstin);
        String headline = r.followUps() == 0 && r.replies() == 0 ? "No follow-ups needed"
                : r.followUps() + " follow-up" + (r.followUps() == 1 ? "" : "s") + ", " + (r.followUps() - r.unanswered())
                + " answered" + (r.promisesMade() == 0 ? "" : " · promises " + r.promisesKept() + " kept, " + r.promisesBroken() + " broken");
        String level = r.followUps() == 0 ? "NONE" : r.followUps() < thin ? "LOW" : "HIGH";
        String text = r.followUps() == 0 ? "No follow-ups on record." : r.followUps() < thin
                ? "Thin history: only " + r.followUps() + " follow-up" + (r.followUps() == 1 ? "" : "s") + "."
                : r.followUps() + " follow-ups on record.";
        Map<String, Long> outcomes = new LinkedHashMap<>();
        outcomes.put("KEPT", (long) r.promisesKept());
        outcomes.put("BROKEN", (long) r.promisesBroken());
        return new DimensionCard(Dimension.RESPONSIVENESS.name(), Dimension.RESPONSIVENESS.label(), Dimension.RESPONSIVENESS.question(),
                r.followUps(), 0, outcomes, headline, r.averageReplyDays(), null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                level, text, List.of(), r);
    }

    private TrackRecord trackRecord(String gstin) {
        List<Recommendation> recs = recommendations.findByVendorGstinOrderByCreatedAtAsc(gstin);
        return new TrackRecord(recs.size(),
                recs.stream().filter(r -> r.getWasCorrect() != null).count(),
                recs.stream().filter(r -> Boolean.TRUE.equals(r.getWasCorrect())).count(),
                recs.stream().filter(r -> r.getDecision() == Recommendation.Decision.ACCEPTED).count(),
                recs.stream().filter(r -> r.getDecision() == Recommendation.Decision.MODIFIED).count(),
                recs.stream().filter(r -> r.getDecision() == Recommendation.Decision.REJECTED).count());
    }

    private List<TimelineEvent> timeline(String gstin, List<Mismatch> cases) {
        List<TimelineEvent> events = new ArrayList<>();
        for (Mismatch m : cases) {
            events.add(new TimelineEvent(m.getDetectedAt(), "DETECTED", m.getType().label() + ": " + m.invoiceNo(),
                    Fmt.month(m.getPeriod()) + " · exposure " + Fmt.inr(m.getExposure()), m.getDimension().name(),
                    m.getExposure(), m.getId(), "neutral"));
            if (m.getVerdict() != null && m.getVerdictAt() != null) {
                boolean bad = m.getVerdict() == Outcome.UNRESOLVED_AT_RISK;
                events.add(new TimelineEvent(m.getVerdictAt(), "OUTCOME", m.getVerdict().label() + ": " + m.invoiceNo(),
                        m.getVerdictNote(), m.getDimension().name(), bad ? m.getExposure() : m.getRecoveredAmount(), m.getId(),
                        bad ? "bad" : "good"));
            }
        }
        for (VendorCommunication c : communications.findByVendorGstinOrderByOccurredAtAsc(gstin)) {
            String title = (c.getDirection() == VendorCommunication.Direction.OUT ? "Follow-up " : "Vendor reply ")
                    + "(" + c.getChannel().name().toLowerCase() + ")";
            events.add(new TimelineEvent(c.getOccurredAt(), "COMMUNICATION", title, c.getSummary(), Dimension.RESPONSIVENESS.name(),
                    null, null, "neutral"));
            if (c.getPromiseStatus() != null && c.getPromiseStatus() != VendorCommunication.PromiseStatus.PENDING
                    && c.getPromiseCheckedAt() != null) {
                boolean kept = c.getPromiseStatus() == VendorCommunication.PromiseStatus.KEPT;
                events.add(new TimelineEvent(c.getPromiseCheckedAt(), "PROMISE", "Promise " + (kept ? "kept" : "broken"),
                        "Promised by " + Fmt.day(c.getPromiseBy()) + (c.invoices().isEmpty() ? "" : " for " + String.join(", ", c.invoices())),
                        Dimension.RESPONSIVENESS.name(), null, null, kept ? "good" : "bad"));
            }
        }
        for (AccountantAction a : actions.findByVendorGstinOrderByOccurredAtAsc(gstin)) {
            boolean writeOff = AccountantAction.WRITE_OFF.equals(a.getAction());
            events.add(new TimelineEvent(a.getOccurredAt(), "ACTION", writeOff ? "ITC reversed (confirmed loss)"
                    : a.getAction().replace('_', ' ').toLowerCase(), a.getNote(), null, null, a.getMismatchId(), writeOff ? "bad" : "neutral"));
        }
        for (Recommendation r : recommendations.findByVendorGstinOrderByCreatedAtAsc(gstin)) {
            events.add(new TimelineEvent(r.getCreatedAt(), "RECOMMENDATION", "Vishwas: " + r.getCategory().label()
                    + (r.getTopCause() == null ? "" : " · " + r.getTopCause().label()), r.getHeadline(), r.getDimension().name(),
                    null, r.getMismatchId(), "neutral"));
            if (r.getDecidedAt() != null) {
                events.add(new TimelineEvent(r.getDecidedAt(), "DECISION", r.getDecidedBy() + " " + r.getDecision().name().toLowerCase()
                        + " the recommendation", r.getDecisionReason() == null ? null : "Reason: " + r.getDecisionReason().name()
                        .replace('_', ' ').toLowerCase() + (r.getDecisionNote() == null ? "" : ". " + r.getDecisionNote()),
                        r.getDimension().name(), null, r.getMismatchId(), "neutral"));
            }
            if (r.getJudgedAt() != null) {
                events.add(new TimelineEvent(r.getJudgedAt(), "JUDGED", "Recommendation proved " + (r.getWasCorrect() ? "right" : "wrong"),
                        "Expected " + r.getPredictedOutcome() + ", actual " + r.getActualOutcome(), r.getDimension().name(), null,
                        r.getMismatchId(), r.getWasCorrect() ? "good" : "bad"));
            }
        }
        events.sort(Comparator.comparing(TimelineEvent::at));
        return events;
    }
}

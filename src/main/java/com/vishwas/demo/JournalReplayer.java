package com.vishwas.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishwas.advisor.Category;
import com.vishwas.advisor.Cause;
import com.vishwas.advisor.Confidence;
import com.vishwas.advisor.MemoryAdvisor;
import com.vishwas.advisor.Recommendation;
import com.vishwas.advisor.RecommendationRepository;
import com.vishwas.advisor.VendorHistoryService;
import com.vishwas.config.VishwasProperties;
import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchRepository;
import com.vishwas.outcomes.AccountantAction;
import com.vishwas.outcomes.AccountantActionRepository;
import com.vishwas.outcomes.VendorCommunication;
import com.vishwas.outcomes.VendorCommunicationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Applies the seeded journal (communications and promises, notes, the ITC write-off, and Vishwas's past
 * recommendations with the accountant's decisions) to the database, event by event in time order.
 */
@Component
public class JournalReplayer {

    private final VendorCommunicationRepository communications;
    private final AccountantActionRepository actions;
    private final RecommendationRepository recommendations;
    private final MismatchRepository mismatches;
    private final VendorHistoryService history;
    private final ObjectMapper json;
    private final int thin;

    public JournalReplayer(VendorCommunicationRepository communications, AccountantActionRepository actions,
                           RecommendationRepository recommendations, MismatchRepository mismatches, VendorHistoryService history,
                           ObjectMapper json, VishwasProperties props) {
        this.communications = communications;
        this.actions = actions;
        this.recommendations = recommendations;
        this.mismatches = mismatches;
        this.history = history;
        this.json = json;
        this.thin = props.advice().thinHistoryCases();
    }

    /** Events with {@code from <= at < to}, in time order. */
    public static List<JsonNode> window(JsonNode journal, Instant from, Instant to) {
        List<JsonNode> out = new ArrayList<>();
        journal.path("events").forEach(e -> {
            Instant at = at(e);
            if (!at.isBefore(from) && at.isBefore(to)) {
                out.add(e);
            }
        });
        out.sort(java.util.Comparator.comparing(JournalReplayer::at));
        return out;
    }

    @Transactional
    public void apply(JsonNode e) {
        switch (e.path("type").asText()) {
            case "COMMUNICATION" -> communications.save(new VendorCommunication(e.path("vendor").asText(), at(e),
                    VendorCommunication.Direction.valueOf(e.path("direction").asText()),
                    VendorCommunication.Channel.valueOf(e.path("channel").asText()), e.path("author").asText(null),
                    e.path("summary").asText(), strings(e.path("invoices")),
                    e.hasNonNull("promise_by") ? LocalDate.parse(e.path("promise_by").asText()) : null,
                    e.path("attachment").asText(null), "JOURNAL"));
            case "NOTE" -> actions.save(new AccountantAction(e.path("vendor").asText(), mismatchId(e), at(e),
                    e.path("author").asText(), AccountantAction.NOTE, e.path("note").asText(), null));
            case "WRITE_OFF" -> {
                Mismatch m = mismatch(e);
                m.writeOff(at(e), e.path("note").asText());
                mismatches.save(m);
                actions.save(new AccountantAction(e.path("vendor").asText(), m.getId(), at(e), e.path("author").asText(),
                        AccountantAction.WRITE_OFF, e.path("note").asText(), e.path("approved_by").asText(null)));
            }
            case "RECOMMENDATION" -> recommendation(e);
            default -> throw new IllegalArgumentException("Unknown journal event " + e.path("type").asText());
        }
    }

    private void recommendation(JsonNode e) {
        Mismatch m = mismatch(e);
        Cause cause = Cause.parse(e.path("cause").asText());
        Recommendation r = new Recommendation(m.getId(), null, m.getVendorGstin(), m.getDimension(),
                Category.valueOf(e.path("category").asText()), Recommendation.Source.MEMORY, at(e));
        try {
            r.describe(e.path("headline").asText(), e.path("next_step").asText(), cause,
                    json.writeValueAsString(List.of(new MemoryAdvisor.Hypothesis(cause, "HIGH", e.path("headline").asText()))),
                    "[]", "[]", "[]", null, e.path("rule").asText(null));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        r.confidence(Confidence.of(history.historyFor(m), thin), null);
        JsonNode d = e.path("decision");
        if (!d.isMissingNode()) {
            r.decide(Recommendation.Decision.valueOf(d.path("decision").asText()), Recommendation.Reason.valueOf(d.path("reason").asText()),
                    d.path("note").asText(null), null, d.path("by").asText(), at(d));
        }
        recommendations.save(r);
    }

    private Long mismatchId(JsonNode e) {
        return e.hasNonNull("invoice") ? mismatch(e).getId() : null;
    }

    private Mismatch mismatch(JsonNode e) {
        String gstin = e.path("vendor").asText();
        String invoice = e.path("invoice").asText();
        String period = e.path("period").asText(null);
        return mismatches.findByVendorGstinOrderByDetectedAtAscIdAsc(gstin).stream()
                .filter(m -> invoice.equalsIgnoreCase(m.invoiceNo()) && (period == null || period.equals(m.getPeriod())))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Journal refers to unknown case " + gstin + " " + invoice + " " + period));
    }

    static Instant at(JsonNode e) {
        return OffsetDateTime.parse(e.path("at").asText()).toInstant();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}

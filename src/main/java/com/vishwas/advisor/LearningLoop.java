package com.vishwas.advisor;

import com.vishwas.matching.Mismatch;
import com.vishwas.matching.MismatchRepository;
import com.vishwas.memory.MemoryWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The experience loop: every recommendation is remembered; the accountant's decision (with a reason) is
 * remembered; and when a later GSTR-2B judges the case, whether the recommendation was right is remembered
 * too. Accuracy per category is computed here from the database, never by the LLM.
 */
@Service
public class LearningLoop {

    public record Accuracy(Category category, long judged, long correct) {
        public Double rate() {
            return judged == 0 ? null : (double) correct / judged;
        }
    }

    private final RecommendationRepository recommendations;
    private final MismatchRepository mismatches;

    public LearningLoop(RecommendationRepository recommendations, MismatchRepository mismatches) {
        this.recommendations = recommendations;
        this.mismatches = mismatches;
    }

    /** Judge every open recommendation on these freshly judged mismatches; returns what memory should learn. */
    @Transactional
    public List<MemoryWriter.JudgedEvent> judge(List<Mismatch> judged, Instant at) {
        List<MemoryWriter.JudgedEvent> events = new ArrayList<>();
        if (judged.isEmpty()) {
            return events;
        }
        Map<Long, Mismatch> byId = new java.util.HashMap<>();
        judged.forEach(m -> byId.put(m.getId(), m));
        for (Recommendation r : recommendations.unjudgedFor(byId.keySet())) {
            Mismatch m = byId.get(r.getMismatchId());
            if (m.getVerdict() == null) {
                continue;
            }
            r.judge(m.getVerdict(), at);
            if (r.getWasCorrect() != null) {
                events.add(new MemoryWriter.JudgedEvent(at, r.getCreatedAt(), m, r.getCategory().name(),
                        r.getPredictedOutcome().name(), m.getVerdict().name(), r.getWasCorrect()));
            }
        }
        return events;
    }

    /** Record the accountant's decision on a recommendation. */
    @Transactional
    public MemoryWriter.DecisionEvent decide(long recommendationId, Recommendation.Decision decision, Recommendation.Reason reason,
                                             String note, String modifiedStep, String by, Instant at) {
        Recommendation r = recommendations.findById(recommendationId)
                .orElseThrow(() -> new NoSuchElementException("No recommendation " + recommendationId));
        r.decide(decision, reason, note, modifiedStep, by, at);
        Mismatch m = mismatches.findById(r.getMismatchId()).orElseThrow();
        return new MemoryWriter.DecisionEvent(at, r.getCreatedAt(), m, r.getCategory().name(),
                r.getTopCause() == null ? "UNKNOWN" : r.getTopCause().name(), decision.name(), reason.name(), note, by);
    }

    /** Recommendation accuracy per category, straight from the database. */
    @Transactional(readOnly = true)
    public List<Accuracy> accuracy() {
        Map<Category, Accuracy> out = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            out.put(c, new Accuracy(c, 0, 0));
        }
        for (Object[] row : recommendations.accuracyByCategory()) {
            Category c = (Category) row[0];
            out.put(c, new Accuracy(c, ((Number) row[1]).longValue(), row[2] == null ? 0 : ((Number) row[2]).longValue()));
        }
        return List.copyOf(out.values());
    }
}

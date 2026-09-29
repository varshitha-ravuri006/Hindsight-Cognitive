package com.vishwas.workflow;

import com.vishwas.advisor.LearningLoop;
import com.vishwas.advisor.Recommendation;
import com.vishwas.config.VishwasProperties;
import com.vishwas.ingest.Fmt;
import com.vishwas.memory.MemoryPublisher;
import com.vishwas.memory.MemoryWriter;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The accountant approves, modifies or rejects a recommendation with a short reason. The decision is stored
 * and remembered, so Vishwas can weigh its own track record next time.
 */
@Service
public class DecisionService {

    private final LearningLoop learning;
    private final MemoryWriter writer;
    private final MemoryPublisher publisher;
    private final Clock clock;
    private final String accountant;
    private final com.vishwas.advisor.RecommendationRepository recommendations;
    private final ActionCenterService actionCenter;

    public DecisionService(LearningLoop learning, MemoryWriter writer, MemoryPublisher publisher, Clock clock, VishwasProperties props,
                           com.vishwas.advisor.RecommendationRepository recommendations, ActionCenterService actionCenter) {
        this.learning = learning;
        this.writer = writer;
        this.publisher = publisher;
        this.clock = clock;
        this.accountant = props.company().accountant();
        this.recommendations = recommendations;
        this.actionCenter = actionCenter;
    }

    public void decide(long recommendationId, Recommendation.Decision decision, Recommendation.Reason reason, String note,
                       String modifiedStep, String by) {
        Instant now = Instant.now(clock);
        MemoryWriter.DecisionEvent event = learning.decide(recommendationId, decision, reason, note, modifiedStep,
                by == null || by.isBlank() ? accountant : by, now);
        publisher.publish("Decision " + Fmt.day(now), Fmt.period(now), List.of(writer.decision(event)));
        // Approving an AUTO_RESOLVE applies the approved deterministic rule: audited and reversible.
        recommendations.findById(recommendationId)
                .filter(r -> decision == Recommendation.Decision.ACCEPTED && r.getCategory() == com.vishwas.advisor.Category.AUTO_RESOLVE
                        && r.getRuleId() != null)
                .ifPresent(r -> actionCenter.applyAutoResolve(r, by));
    }
}

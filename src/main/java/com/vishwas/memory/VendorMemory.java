package com.vishwas.memory;

import com.vishwas.config.VishwasProperties;
import com.vishwas.ingest.Fmt;
import com.vishwas.matching.Dimension;
import com.vishwas.memory.hindsight.HindsightClient;
import com.vishwas.memory.hindsight.HindsightException;
import com.vishwas.memory.hindsight.MemoryUnit;
import com.vishwas.memory.hindsight.ObservationChange;
import com.vishwas.memory.hindsight.RecallHit;
import com.vishwas.memory.hindsight.RecallQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads what Hindsight has learned about one vendor. Every read is scoped by the vendor's GSTIN tag with
 * {@code any_strict} matching, so a look-alike vendor's memories can never leak in.
 */
@Component
public class VendorMemory {

    private static final Logger log = LoggerFactory.getLogger(VendorMemory.class);

    /** A consolidated belief (observation) and the dimension it is about, when scoped to one. */
    public record Belief(String id, String text, String dimension, List<String> tags) {
    }

    /** A raw fact behind the beliefs, for the evidence drawer. */
    public record Fact(String id, String text, String context, String occurredAt, String documentId,
                       Map<String, Object> metadata, List<String> tags) {
        public static Fact of(RecallHit h) {
            return new Fact(h.id(), h.text(), h.context(), h.occurredStart() != null ? h.occurredStart() : h.mentionedAt(),
                    h.documentId(), h.metadata(), h.tags());
        }
    }

    /** One version of a belief: its text, when it became current, which month's facts caused it. */
    public record BeliefVersion(String text, String changedAt, String becauseOf, List<String> newFacts) {
    }

    public record BeliefHistory(String observationId, String dimension, String current, List<BeliefVersion> versions) {
    }

    private final HindsightClient hindsight;
    private final MemoryBatchRepository batches;
    private final String bankId;

    public VendorMemory(HindsightClient hindsight, MemoryBatchRepository batches, VishwasProperties props) {
        this.hindsight = hindsight;
        this.batches = batches;
        this.bankId = props.hindsight().bankId();
    }

    /** "What Vishwas learned": observations scoped to this vendor, grouped by dimension where scoped. */
    public List<Belief> learned(String gstin, String vendorName) {
        var query = RecallQuery.of("How does " + vendorName + " behave on filing timing, amount accuracy, tax heads, "
                        + "invoice format, responsiveness and duplicates, and what did past mismatches turn out to be?",
                List.of("observation"), List.of(MemoryWriter.vendorTag(gstin)), "any_strict");
        return hindsight.recall(bankId, query).hits().stream()
                .map(h -> new Belief(h.id(), h.text(), dimensionOf(h.tags()), h.tags()))
                .toList();
    }

    /** Raw facts (world + experience) about this vendor that answer {@code question}. */
    public List<Fact> facts(String gstin, String question) {
        var query = RecallQuery.of(question, List.of("world", "experience"), List.of(MemoryWriter.vendorTag(gstin)), "any_strict");
        return hindsight.recall(bankId, query).hits().stream().map(Fact::of).toList();
    }

    /**
     * "How Vishwas's view of this vendor changed": the observation scoped to exactly {vendor, dimension},
     * with every earlier version from {@code GET /memories/{id}/history}. Each change is labelled with the
     * history month whose facts caused it (by matching the change time to the batch that was being loaded).
     */
    public Optional<BeliefHistory> beliefHistory(String gstin, Dimension dim) {
        List<MemoryUnit> obs = hindsight.listMemories(bankId, "observation",
                List.of(MemoryWriter.vendorTag(gstin), dim.tag()), "exact", null, 5, 0).rows();
        if (obs.isEmpty()) {
            return Optional.empty();
        }
        MemoryUnit current = obs.get(0);
        List<ObservationChange> changes;
        try {
            changes = new ArrayList<>(hindsight.observationHistory(bankId, current.id()));
        } catch (HindsightException e) {
            log.warn("Observation history unavailable for {}: {}", current.id(), e.getMessage());
            changes = new ArrayList<>();
        }
        changes.sort(Comparator.comparing(c -> parse(c.changedAt()), Comparator.nullsFirst(Comparator.naturalOrder())));
        List<MemoryBatch> log = batches.findAllByOrderByStartedAtAsc();

        List<BeliefVersion> versions = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++) {
            ObservationChange c = changes.get(i);
            String becameAt = i == 0 ? null : changes.get(i - 1).changedAt();
            versions.add(new BeliefVersion(c.previousText(), becameAt, i == 0 ? firstLabel(log) : labelFor(becameAt, log),
                    i == 0 ? List.of() : newFacts(changes.get(i - 1))));
        }
        String lastChange = changes.isEmpty() ? null : changes.get(changes.size() - 1).changedAt();
        versions.add(new BeliefVersion(current.text(), lastChange, changes.isEmpty() ? firstLabel(log) : labelFor(lastChange, log),
                changes.isEmpty() ? List.of() : newFacts(changes.get(changes.size() - 1))));
        return Optional.of(new BeliefHistory(current.id(), dim.name(), current.text(), versions));
    }

    private static List<String> newFacts(ObservationChange c) {
        if (c.sourceFacts() == null) {
            return List.of();
        }
        return c.sourceFacts().stream().filter(f -> Boolean.TRUE.equals(f.isNew()) && f.text() != null)
                .map(ObservationChange.SourceFact::text).toList();
    }

    /** The batch that was being processed when the change happened (or the latest one started before it). */
    static String labelFor(String changedAt, List<MemoryBatch> log) {
        Instant at = parse(changedAt);
        if (at == null || log.isEmpty()) {
            return null;
        }
        MemoryBatch best = null;
        for (MemoryBatch b : log) {
            if (!b.getStartedAt().isAfter(at)) {
                best = b;
            }
        }
        return best == null ? log.get(0).getLabel() : best.getLabel();
    }

    private static String firstLabel(List<MemoryBatch> log) {
        return log.isEmpty() ? null : log.get(0).getLabel();
    }

    static Instant parse(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (RuntimeException e) {
            try {
                return java.time.LocalDateTime.parse(iso).atZone(java.time.ZoneOffset.UTC).toInstant();
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    static String dimensionOf(List<String> tags) {
        if (tags == null) {
            return null;
        }
        return tags.stream().filter(t -> t.startsWith("dim:")).map(t -> t.substring(4)).findFirst().orElse(null);
    }

    /** Month label helper for callers building batch labels. */
    public static String label(String period) {
        return Fmt.month(period);
    }
}

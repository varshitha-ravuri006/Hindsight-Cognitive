package com.vishwas.memory.hindsight;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code POST /memories/recall}. {@code types} null = world, experience and observation.
 * {@code queryTimestamp} anchors relative expressions ("last month"); {@code temporalWindow} pins the
 * temporal retrieval arm to an explicit range.
 */
public record RecallQuery(
        String query,
        List<String> types,
        List<String> tags,
        String tagsMatch,
        String budget,
        Integer maxTokens,
        String queryTimestamp,
        TemporalWindow temporalWindow,
        Map<String, Object> include) {

    public record TemporalWindow(String start, String end) {
    }

    public static RecallQuery of(String query, List<String> types, List<String> tags, String tagsMatch) {
        return new RecallQuery(query, types, tags, tagsMatch, "mid", 4096, null, null, null);
    }

    public RecallQuery withTime(String anchor, TemporalWindow window) {
        return new RecallQuery(query, types, tags, tagsMatch, budget, maxTokens, anchor, window, include);
    }

    public RecallQuery withSourceFacts() {
        return new RecallQuery(query, types, tags, tagsMatch, budget, maxTokens, queryTimestamp, temporalWindow,
                Map.of("source_facts", Map.of()));
    }
}

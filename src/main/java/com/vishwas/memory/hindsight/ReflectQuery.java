package com.vishwas.memory.hindsight;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code POST /reflect}. Vishwas always includes facts (so every recommendation can show the
 * memories behind it) and scopes by vendor tag so look-alike vendors never share evidence.
 */
public record ReflectQuery(
        String query,
        String budget,
        List<String> tags,
        String tagsMatch,
        Boolean applyAllDirectives,
        Map<String, Object> include,
        Object responseSchema,
        Integer maxTokens) {

    /** Scoped to memories carrying at least one of {@code tags}; untagged memories are excluded. */
    public static ReflectQuery scoped(String query, List<String> tags, String budget, Object schema, int maxTokens) {
        return new ReflectQuery(query, budget, tags, "any_strict", true, Map.of("facts", Map.of()), schema, maxTokens);
    }
}

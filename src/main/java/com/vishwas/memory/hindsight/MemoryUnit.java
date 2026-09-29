package com.vishwas.memory.hindsight;

import java.util.List;
import java.util.Map;

/** One row of {@code GET /memories/list} (and of {@code GET /memories/{id}}). */
public record MemoryUnit(
        String id,
        String text,
        String context,
        String date,
        String factType,
        String type,
        String documentId,
        String mentionedAt,
        String occurredStart,
        String occurredEnd,
        List<String> tags,
        Map<String, Object> metadata,
        String state,
        String invalidationReason,
        String invalidatedAt,
        String editedAt,
        String updatedAt,
        List<String> sourceMemoryIds,
        Integer proofCount) {

    public String kind() {
        return factType != null ? factType : type;
    }
}

package com.vishwas.memory.hindsight;

import java.util.List;
import java.util.Map;

/** One recall result; also the shape of reflect's {@code based_on.memories} and of recall's source facts. */
public record RecallHit(
        String id,
        String text,
        String type,
        String context,
        String occurredStart,
        String occurredEnd,
        String mentionedAt,
        String documentId,
        List<String> tags,
        Map<String, Object> metadata,
        List<String> sourceFactIds) {
}

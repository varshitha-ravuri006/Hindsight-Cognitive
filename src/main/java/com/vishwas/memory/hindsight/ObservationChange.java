package com.vishwas.memory.hindsight;

import java.util.List;

/**
 * One entry of {@code GET /memories/{id}/history} (most recent first): what the observation said BEFORE
 * this change, when it changed (wall-clock), and the source facts behind it ({@code isNew} = the facts
 * that caused this change).
 */
public record ObservationChange(
        String previousText,
        List<String> previousTags,
        String previousOccurredStart,
        String previousOccurredEnd,
        String changedAt,
        List<String> newSourceMemoryIds,
        List<SourceFact> sourceFacts) {

    public record SourceFact(String id, String text, String type, String context, Boolean isNew) {
    }
}

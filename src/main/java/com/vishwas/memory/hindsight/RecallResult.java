package com.vishwas.memory.hindsight;

import java.util.List;
import java.util.Map;

/** Response of {@code POST /memories/recall}: ranked results plus, when requested, observations' source facts. */
public record RecallResult(List<RecallHit> results, Map<String, RecallHit> sourceFacts) {

    public List<RecallHit> hits() {
        return results == null ? List.of() : results;
    }

    public Map<String, RecallHit> sources() {
        return sourceFacts == null ? Map.of() : sourceFacts;
    }
}

package com.vishwas.memory.hindsight;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Response of {@code POST /reflect}: the answer, the evidence it used and (optionally) structured output. */
public record ReflectAnswer(String text, BasedOn basedOn, JsonNode structuredOutput, String structuredOutputError) {

    public record BasedOn(List<RecallHit> memories, List<Named> mentalModels, List<Named> directives) {
    }

    public record Named(String id, String name, String text, String content) {
    }

    public List<RecallHit> sourceMemories() {
        return basedOn == null || basedOn.memories() == null ? List.of() : basedOn.memories();
    }

    public List<Named> directivesUsed() {
        return basedOn == null || basedOn.directives() == null ? List.of() : basedOn.directives();
    }
}

package com.vishwas.memory.hindsight;

/**
 * Body of {@code PATCH /memories/{id}} (curation). Only world/experience facts can be curated; editing the
 * text re-embeds the fact and re-consolidates its observations. {@code state} is "invalidated" or "valid".
 */
public record UpdateMemoryRequest(String text, String state, String reason) {
}

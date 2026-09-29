package com.vishwas.memory.hindsight;

import java.util.List;
import java.util.Map;

/**
 * One item for {@code POST /memories} (retain). Field names are serialised snake_case by the apiJson mapper.
 *
 * @param content           plain-English text; Hindsight extracts facts from it
 * @param timestamp         ISO-8601 time the event HAPPENED (history is backdated to the real event date)
 * @param context           short label that steers extraction ("mismatch detected", "mismatch outcome", ...)
 * @param documentId        stable id so re-retaining the same event upserts instead of duplicating
 * @param tags              vendor:GSTIN, dim:DIMENSION, type:MISMATCH_TYPE, period:YYYY-MM
 * @param metadata          string map echoed back on recall, used by the evidence drawer
 * @param observationScopes explicit tag-set lists: per vendor, per vendor+dimension, per dimension
 * @param entities          the vendor's legal name and GSTIN, passed explicitly
 * @param resolveEntities   false, so look-alike vendor names are never merged
 * @param updateMode        "append" for vendor communication threads, otherwise null (= replace)
 */
public record MemoryItem(
        String content,
        String timestamp,
        String context,
        String documentId,
        List<String> tags,
        Map<String, String> metadata,
        List<List<String>> observationScopes,
        List<EntityInput> entities,
        Boolean resolveEntities,
        String updateMode) {

    public record EntityInput(String text, String type) {
    }
}

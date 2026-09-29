package com.vishwas.memory;

import com.vishwas.config.VishwasProperties;
import com.vishwas.memory.hindsight.HindsightClient;
import com.vishwas.memory.hindsight.HindsightException;
import com.vishwas.memory.hindsight.MemoryUnit;
import com.vishwas.memory.hindsight.UpdateMemoryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * "Correct this history". Hindsight curates facts, not observations (observations are derived), so a wrong belief
 * is corrected at its source: edit the fact's text, or invalidate it with a reason (reversible). Every correction
 * is audited here (who, when, why, before and after) before it is sent, and afterwards the vendor's beliefs are
 * re-consolidated so the observation is rebuilt without the wrong fact.
 */
@Service
public class CurationService {

    private static final Logger log = LoggerFactory.getLogger(CurationService.class);

    public record Fact(String id, String text, String context, String date, String state, String invalidationReason,
                       String editedAt, List<String> tags) {
    }

    private final HindsightClient hindsight;
    private final MemoryCorrectionRepository corrections;
    private final MemoryStats stats;
    private final Clock clock;
    private final String bankId;

    public CurationService(HindsightClient hindsight, MemoryCorrectionRepository corrections, MemoryStats stats, Clock clock,
                           VishwasProperties props) {
        this.hindsight = hindsight;
        this.corrections = corrections;
        this.stats = stats;
        this.clock = clock;
        this.bankId = props.hindsight().bankId();
    }

    /** The vendor's raw facts (world and experience), newest first, including invalidated ones. */
    public List<Fact> facts(String gstin) {
        List<Fact> out = new ArrayList<>();
        List<MemoryUnit> units = new ArrayList<>(
                hindsight.listMemories(bankId, null, List.of(MemoryWriter.vendorTag(gstin)), "any_strict", null, 100, 0).rows());
        // Invalidated facts are hidden from the default listing; fetch them too so they can be restored.
        try {
            units.addAll(hindsight.listMemories(bankId, null, List.of(MemoryWriter.vendorTag(gstin)), "any_strict", null,
                    "invalidated", 50, 0).rows());
        } catch (HindsightException e) {
            log.debug("Could not list invalidated facts: {}", e.getMessage());
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (MemoryUnit u : units) {
            if (!seen.add(u.id())) {
                continue;
            }
            if ("observation".equals(u.kind())) {
                continue;
            }
            out.add(new Fact(u.id(), u.text(), u.context(), u.occurredStart() != null ? u.occurredStart() : u.date(),
                    u.state() == null ? "valid" : u.state(), u.invalidationReason(), u.editedAt(), u.tags()));
        }
        return out;
    }

    public List<MemoryCorrection> history(String gstin) {
        return corrections.findByVendorGstinOrderByCreatedAtDesc(gstin);
    }

    /**
     * @param action  EDIT (needs newText), INVALIDATE, or REVERT (make an invalidated fact valid again)
     */
    public MemoryCorrection correct(String gstin, String memoryId, MemoryCorrection.Action action, String newText, String reason,
                                    String actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Say why the history is being corrected.");
        }
        if (action == MemoryCorrection.Action.EDIT && (newText == null || newText.isBlank())) {
            throw new IllegalArgumentException("An edit needs the corrected text.");
        }
        MemoryUnit current;
        try {
            current = hindsight.getMemory(bankId, memoryId);
        } catch (HindsightException e) {
            if (e.notFound()) {
                throw new NoSuchElementException("No memory " + memoryId);
            }
            throw e;
        }
        if ("observation".equals(current.kind())) {
            throw new IllegalArgumentException("Observations are derived; correct the fact behind them instead.");
        }
        if (current.tags() != null && !current.tags().contains(MemoryWriter.vendorTag(gstin))) {
            throw new IllegalArgumentException("That memory does not belong to this vendor.");
        }
        MemoryCorrection row = corrections.save(new MemoryCorrection(memoryId, gstin, action, current.text(),
                action == MemoryCorrection.Action.EDIT ? newText.trim() : null, reason.trim(), actor, Instant.now(clock)));
        try {
            UpdateMemoryRequest body = switch (action) {
                case EDIT -> new UpdateMemoryRequest(newText.trim(), null, null);
                case INVALIDATE -> new UpdateMemoryRequest(null, "invalidated", reason.trim() + " (" + actor + ")");
                case REVERT -> new UpdateMemoryRequest(null, "valid", null);
            };
            hindsight.updateMemory(bankId, memoryId, body);
            rebuild(gstin, current.tags());
            row.done();
            log.info("Memory {} {} by {}: {}", memoryId, action, actor, reason);
        } catch (HindsightException e) {
            row.failed(e.getMessage());
            log.warn("Correction of {} failed: {}", memoryId, e.getMessage());
        }
        stats.invalidate();
        return corrections.save(row);
    }

    /** Re-consolidate the vendor's beliefs (vendor-wide and for the dimension of the corrected fact). */
    private void rebuild(String gstin, List<String> tags) {
        List<List<String>> scopes = new ArrayList<>();
        scopes.add(List.of(MemoryWriter.vendorTag(gstin)));
        if (tags != null) {
            tags.stream().filter(t -> t.startsWith("dim:")).findFirst()
                    .ifPresent(dim -> scopes.add(List.of(MemoryWriter.vendorTag(gstin), dim)));
        }
        try {
            hindsight.consolidate(bankId, scopes);
        } catch (HindsightException e) {
            log.info("Explicit consolidation not accepted ({}); Hindsight re-consolidates edited facts on its own", e.getMessage());
        }
    }
}

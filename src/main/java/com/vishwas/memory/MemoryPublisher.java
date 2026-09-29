package com.vishwas.memory;

import com.vishwas.config.VishwasProperties;
import com.vishwas.memory.hindsight.FileUpload;
import com.vishwas.memory.hindsight.HindsightClient;
import com.vishwas.memory.hindsight.HindsightException;
import com.vishwas.memory.hindsight.MemoryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends live events to Hindsight as one async retain per step (so a request never waits on fact
 * extraction). Failures are logged and remembered for the status banner; they never fail the request.
 */
@Component
public class MemoryPublisher {

    private static final Logger log = LoggerFactory.getLogger(MemoryPublisher.class);

    private final HindsightClient hindsight;
    private final MemoryHealth health;
    private final MemoryBatchRepository batches;
    private final Clock clock;
    private final String bankId;
    private final AtomicInteger sent = new AtomicInteger();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public MemoryPublisher(HindsightClient hindsight, MemoryHealth health, MemoryBatchRepository batches, Clock clock,
                           VishwasProperties props) {
        this.hindsight = hindsight;
        this.health = health;
        this.batches = batches;
        this.clock = clock;
        this.bankId = props.hindsight().bankId();
    }

    public boolean enabled() {
        return hindsight.configured() && health.ready();
    }

    /** Async retain of one step's items; returns the operation id, or empty when memory is off or failed. */
    public Optional<String> publish(String label, String period, List<MemoryItem> items) {
        if (items.isEmpty() || !enabled()) {
            return Optional.empty();
        }
        Instant started = Instant.now(clock);
        MemoryBatch batch = batches.save(new MemoryBatch(label, period, items.size(), started));
        try {
            String op = hindsight.retain(bankId, items, true);
            batch.operation(op);
            batch.finish("SUBMITTED", Instant.now(clock));
            batches.save(batch);
            sent.addAndGet(items.size());
            lastError.set(null);
            log.info("Retained {} memories for '{}' (operation {})", items.size(), label, op);
            return Optional.ofNullable(op);
        } catch (HindsightException e) {
            batch.finish("FAILED", Instant.now(clock));
            batches.save(batch);
            lastError.set(e.getMessage());
            log.warn("Could not retain {} memories for '{}': {}", items.size(), label, e.getMessage());
            return Optional.empty();
        }
    }

    public List<String> publishFiles(List<FileUpload> files) {
        if (files.isEmpty() || !enabled()) {
            return List.of();
        }
        try {
            return hindsight.retainFiles(bankId, files);
        } catch (HindsightException e) {
            lastError.set(e.getMessage());
            log.warn("Could not retain {} files: {}", files.size(), e.getMessage());
            return List.of();
        }
    }

    public int sent() {
        return sent.get();
    }

    public String lastError() {
        return lastError.get();
    }
}

package com.vishwas.memory;

import com.vishwas.config.VishwasProperties;
import com.vishwas.memory.hindsight.FileUpload;
import com.vishwas.memory.hindsight.HindsightClient;
import com.vishwas.memory.hindsight.HindsightException;
import com.vishwas.memory.hindsight.MemoryItem;
import com.vishwas.memory.hindsight.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Feeds the history into Hindsight one month at a time: each month is ONE async retain (plus its letters
 * through /files/retain), then we wait for extraction and for consolidation before the next month. That is
 * how an accountant's memory really forms, and it is what makes observations evolve month by month, which
 * the belief history then shows. Progress is exposed for the UI. When a consolidation webhook is registered
 * it ends each wait early; otherwise /operations is polled.
 */
@Component
public class HistoryMemoryLoader {

    private static final Logger log = LoggerFactory.getLogger(HistoryMemoryLoader.class);
    private static final Duration GIVE_UP_PER_BATCH = Duration.ofMinutes(12);

    /** One chronological slice of history. */
    public record Batch(String label, String period, List<MemoryItem> items, List<FileUpload> files) {
    }

    public record Progress(boolean busy, int batch, int batches, String stage, int itemsSent, int itemsTotal,
                           Instant startedAt, Instant finishedAt, String error) {
        static final Progress IDLE = new Progress(false, 0, 0, "idle", 0, 0, null, null, null);
    }

    private final HindsightClient hindsight;
    private final MemoryBatchRepository batchLog;
    private final ConsolidationSignal signal;
    private final Clock clock;
    private final String bankId;
    private final long pollMs;
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicReference<Progress> progress = new AtomicReference<>(Progress.IDLE);

    public HistoryMemoryLoader(HindsightClient hindsight, MemoryBatchRepository batchLog, ConsolidationSignal signal,
                               Clock clock, VishwasProperties props) {
        this.hindsight = hindsight;
        this.batchLog = batchLog;
        this.signal = signal;
        this.clock = clock;
        this.bankId = props.hindsight().bankId();
        this.pollMs = props.memory() != null && props.memory().processingPollMs() > 0 ? props.memory().processingPollMs() : 3000;
    }

    /** Starts the load in the background and returns immediately. {@code afterLoad} runs once everything settled. */
    public void load(List<Batch> batches, Runnable afterLoad) {
        int gen = generation.incrementAndGet();
        int total = batches.stream().mapToInt(b -> b.items().size() + b.files().size()).sum();
        Instant started = Instant.now(clock);
        progress.set(new Progress(true, 1, batches.size(), "queued", 0, total, started, null, null));
        Thread.ofVirtual().name("history-memory-load").start(() -> run(gen, batches, total, started, afterLoad));
    }

    /** Stops a running load (used by reset). */
    public void cancel() {
        generation.incrementAndGet();
        progress.set(Progress.IDLE);
    }

    public Progress progress() {
        return progress.get();
    }

    private void run(int gen, List<Batch> batches, int total, Instant started, Runnable afterLoad) {
        int sent = 0;
        try {
            for (int i = 0; i < batches.size(); i++) {
                Batch b = batches.get(i);
                int n = i + 1;
                set(gen, new Progress(true, n, batches.size(), "Remembering " + b.label(), sent, total, started, null, null));
                MemoryBatch logged = batchLog.save(new MemoryBatch(b.label(), b.period(), b.items().size() + b.files().size(), Instant.now(clock)));
                Instant since = Instant.now(clock).minusSeconds(2);
                List<String> ops = new ArrayList<>();
                String op = hindsight.retain(bankId, b.items(), true);
                if (op != null) {
                    ops.add(op);
                    logged.operation(op);
                }
                ops.addAll(b.files().isEmpty() ? List.of() : hindsight.retainFiles(bankId, b.files()));
                for (String o : ops) {
                    waitForOperation(gen, o);
                }
                sent += b.items().size() + b.files().size();
                set(gen, new Progress(true, n, batches.size(), "Forming beliefs from " + b.label(), sent, total, started, null, null));
                waitUntilSettled(gen, since);
                logged.finish("DONE", Instant.now(clock));
                batchLog.save(logged);
                if (generation.get() != gen) {
                    return;
                }
            }
            if (generation.get() == gen) {
                set(gen, new Progress(true, batches.size(), batches.size(), "Refreshing mental models", total, total, started, null, null));
                afterLoad.run();
                progress.set(new Progress(false, batches.size(), batches.size(), "done", total, total, started, Instant.now(clock), null));
                log.info("History remembered in {} monthly batches ({} items)", batches.size(), total);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("History memory load stopped: {}", e.getMessage());
            set(gen, new Progress(false, 0, batches.size(), "failed", sent, total, started, Instant.now(clock), e.getMessage()));
        }
    }

    private void waitForOperation(int gen, String operationId) throws InterruptedException {
        Instant deadline = Instant.now().plus(GIVE_UP_PER_BATCH);
        while (Instant.now().isBefore(deadline) && generation.get() == gen) {
            Thread.sleep(pollMs);
            try {
                Operation op = hindsight.getOperation(bankId, operationId);
                if (op.failed()) {
                    throw new IllegalStateException("Hindsight could not process a history batch: " + op.errorMessage());
                }
                if (op.status() == null || "completed".equals(op.status()) || "not_found".equals(op.status())) {
                    return;
                }
            } catch (HindsightException e) {
                log.debug("Polling operation failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Consolidation is queued shortly after extraction. Settled = a consolidation for this batch completed (or
     * the webhook said so) and nothing is running for two polls in a row, or nothing was queued for a while.
     */
    private void waitUntilSettled(int gen, Instant since) throws InterruptedException {
        Instant deadline = Instant.now().plus(GIVE_UP_PER_BATCH);
        Instant quietFallback = Instant.now().plus(Duration.ofMillis(pollMs * 8));
        int quiet = 0;
        while (Instant.now().isBefore(deadline) && generation.get() == gen) {
            Thread.sleep(pollMs);
            try {
                List<Operation> ops = hindsight.listOperations(bankId, 50).stream().filter(o -> createdAfter(o, since)).toList();
                boolean busy = ops.stream().anyMatch(Operation::busy);
                boolean consolidated = signal.completedSince(since) || ops.stream()
                        .anyMatch(o -> "consolidation".equals(o.kind()) && "completed".equals(o.status()));
                quiet = busy ? 0 : quiet + 1;
                if (quiet >= 2 && (consolidated || Instant.now().isAfter(quietFallback))) {
                    return;
                }
            } catch (HindsightException e) {
                log.debug("Polling Hindsight failed: {}", e.getMessage());
            }
        }
    }

    private void set(int gen, Progress p) {
        if (generation.get() == gen) {
            progress.set(p);
        }
    }

    private static boolean createdAfter(Operation o, Instant since) {
        try {
            return o.createdAt() != null && OffsetDateTime.parse(o.createdAt()).toInstant().isAfter(since);
        } catch (RuntimeException e) {
            return true;
        }
    }
}

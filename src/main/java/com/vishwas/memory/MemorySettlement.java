package com.vishwas.memory;

import com.vishwas.config.VishwasProperties;
import com.vishwas.memory.hindsight.HindsightClient;
import com.vishwas.memory.hindsight.HindsightException;
import com.vishwas.memory.hindsight.Operation;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Is memory still digesting what we sent since a moment in time? Answers from Hindsight's operations list:
 * extraction (retain, file conversion), consolidation into observations, and mental-model refreshes. Stateless,
 * so the UI and the demo-prep script can simply poll it.
 */
@Component
public class MemorySettlement {

    /** After the last operation finished, wait this long for a follow-up consolidation to be queued. */
    static final Duration QUIET_GRACE = Duration.ofSeconds(20);

    public enum Stage { OFF, EXTRACTING, CONSOLIDATING, REFRESHING_MODELS, SETTLED }

    public record Status(Stage stage, int busyOperations, int completedOperations, int failedOperations,
                         boolean consolidationCompleted, long elapsedMs, String lastActivityAt, String message) {
    }

    private final HindsightClient hindsight;
    private final MemoryHealth health;
    private final ConsolidationSignal signal;
    private final String bankId;

    public MemorySettlement(HindsightClient hindsight, MemoryHealth health, ConsolidationSignal signal, VishwasProperties props) {
        this.hindsight = hindsight;
        this.health = health;
        this.signal = signal;
        this.bankId = props.hindsight().bankId();
    }

    public Status status(Instant since) {
        long elapsed = Duration.between(since, Instant.now()).toMillis();
        if (!health.ready()) {
            return new Status(Stage.OFF, 0, 0, 0, false, elapsed, null, "Memory is off.");
        }
        List<Operation> ops;
        try {
            ops = hindsight.listOperations(bankId, 100).stream().filter(o -> after(o.createdAt(), since)).toList();
        } catch (HindsightException e) {
            return new Status(Stage.CONSOLIDATING, 0, 0, 0, false, elapsed, null, "Could not reach Hindsight: " + e.getMessage());
        }
        return classify(ops, since, signal.completedSince(since), Instant.now());
    }

    /** Pure decision, unit-tested: which stage do these operations (created after {@code since}) put us in? */
    static Status classify(List<Operation> ops, Instant since, boolean webhookSaysConsolidated, Instant now) {
        long elapsed = Duration.between(since, now).toMillis();
        List<Operation> busy = ops.stream().filter(Operation::busy).toList();
        int failed = (int) ops.stream().filter(Operation::failed).count();
        int done = (int) ops.stream().filter(o -> "completed".equals(o.status())).count();
        boolean consolidated = webhookSaysConsolidated
                || ops.stream().anyMatch(o -> "consolidation".equals(o.kind()) && "completed".equals(o.status()));
        Instant last = ops.stream().map(o -> parse(o.completedAt() != null ? o.completedAt() : o.createdAt()))
                .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
        String lastIso = last == null ? null : last.toString();

        if (busy.stream().anyMatch(o -> "retain".equals(o.kind()) || "file_convert_retain".equals(o.kind()))) {
            return new Status(Stage.EXTRACTING, busy.size(), done, failed, consolidated, elapsed, lastIso,
                    "Extracting facts from what just happened.");
        }
        if (busy.stream().anyMatch(o -> "consolidation".equals(o.kind()))) {
            return new Status(Stage.CONSOLIDATING, busy.size(), done, failed, consolidated, elapsed, lastIso,
                    "Consolidating the new facts into beliefs.");
        }
        if (busy.stream().anyMatch(o -> "refresh_mental_model".equals(o.kind()))) {
            return new Status(Stage.REFRESHING_MODELS, busy.size(), done, failed, consolidated, elapsed, lastIso,
                    "Beliefs updated; refreshing the mental models.");
        }
        if (!busy.isEmpty()) {
            return new Status(Stage.CONSOLIDATING, busy.size(), done, failed, consolidated, elapsed, lastIso, "Memory is busy.");
        }
        boolean quietLongEnough = last == null ? elapsed > QUIET_GRACE.toMillis() * 3
                : Duration.between(last, now).compareTo(QUIET_GRACE) >= 0;
        if (consolidated || quietLongEnough) {
            return new Status(Stage.SETTLED, 0, done, failed, consolidated, elapsed, lastIso, "Memory is up to date.");
        }
        return new Status(Stage.CONSOLIDATING, 0, done, failed, false, elapsed, lastIso, "Waiting for consolidation to start.");
    }

    private static boolean after(String iso, Instant since) {
        Instant at = parse(iso);
        return at == null || !at.isBefore(since);
    }

    static Instant parse(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (RuntimeException e) {
            try {
                return java.time.LocalDateTime.parse(iso).atZone(java.time.ZoneOffset.UTC).toInstant();
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }
}

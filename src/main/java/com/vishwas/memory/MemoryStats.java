package com.vishwas.memory;

import com.vishwas.config.VishwasProperties;
import com.vishwas.memory.hindsight.HindsightClient;
import com.vishwas.memory.hindsight.HindsightException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** Memory and observation counts for the header chips, cached briefly so the UI can poll freely. */
@Component
public class MemoryStats {

    public record Counts(Integer memories, Integer observations, Instant at, String error) {
    }

    private static final Duration TTL = Duration.ofSeconds(8);

    private final HindsightClient hindsight;
    private final MemoryHealth health;
    private final String bankId;
    private final AtomicReference<Counts> cached = new AtomicReference<>(new Counts(null, null, Instant.EPOCH, null));

    public MemoryStats(HindsightClient hindsight, MemoryHealth health, VishwasProperties props) {
        this.hindsight = hindsight;
        this.health = health;
        this.bankId = props.hindsight().bankId();
    }

    public Counts counts() {
        Counts c = cached.get();
        if (!health.ready() || Instant.now().isBefore(c.at().plus(TTL))) {
            return c;
        }
        try {
            int all = hindsight.countMemories(bankId, null);
            int obs = hindsight.countMemories(bankId, "observation");
            c = new Counts(all, obs, Instant.now(), null);
        } catch (HindsightException e) {
            c = new Counts(c.memories(), c.observations(), Instant.now(), e.getMessage());
        }
        cached.set(c);
        return c;
    }

    public void invalidate() {
        cached.set(new Counts(null, null, Instant.EPOCH, null));
    }
}

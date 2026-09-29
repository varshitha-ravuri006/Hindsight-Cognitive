package com.vishwas.memory;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Set by the Hindsight webhook ({@code consolidation.completed}) when one is registered. Waiters use it to
 * stop polling early; without a webhook they simply poll /operations.
 */
@Component
public class ConsolidationSignal {

    private final AtomicReference<Instant> lastCompleted = new AtomicReference<>(Instant.EPOCH);
    private final AtomicInteger received = new AtomicInteger();

    public void completed(Instant at) {
        lastCompleted.set(at);
        received.incrementAndGet();
    }

    public boolean completedSince(Instant since) {
        return lastCompleted.get().isAfter(since);
    }

    public int received() {
        return received.get();
    }
}

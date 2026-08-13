package com.lumora.pos.sync;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * What the terminal's status strip reads.
 *
 * <p>Held in memory rather than persisted: it describes this process's connectivity right now, and a
 * restart legitimately knows nothing until the first tick. The pending count — the number that
 * actually matters to a shopkeeper — comes from the outbox itself, which is durable.
 */
@Component
@Profile("desktop")
public class SyncStatus {

    private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastAttemptAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public void recordSuccess() {
        Instant now = Instant.now();
        lastAttemptAt.set(now);
        lastSuccessAt.set(now);
        lastError.set(null);
    }

    public void recordFailure(String error) {
        lastAttemptAt.set(Instant.now());
        lastError.set(error);
    }

    public Instant lastSuccessAt() {
        return lastSuccessAt.get();
    }

    public Instant lastAttemptAt() {
        return lastAttemptAt.get();
    }

    public String lastError() {
        return lastError.get();
    }

    /**
     * Online means the last attempt actually succeeded — not that a cable is plugged in. Before the
     * first attempt this is false, which is the honest answer: we do not know yet.
     */
    public boolean online() {
        return lastError.get() == null && lastSuccessAt.get() != null;
    }
}

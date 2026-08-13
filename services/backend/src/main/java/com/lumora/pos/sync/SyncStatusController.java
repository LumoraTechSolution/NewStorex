package com.lumora.pos.sync;

import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the terminal's status strip polls.
 *
 * <p>The pending count is a trust feature, not a debug stat. A shopkeeper who can see "12 sales
 * saved locally, waiting to sync" understands what happens when the internet goes far better than
 * any amount of reassurance — and a count that never falls is how they find out something is wrong
 * before their accountant does.
 */
@RestController
@RequestMapping("/api/sync")
@Profile("desktop")
public class SyncStatusController {

    private final SyncWorker worker;
    private final SyncStatus status;

    public SyncStatusController(SyncWorker worker, SyncStatus status) {
        this.worker = worker;
        this.status = status;
    }

    @GetMapping("/status")
    public Status status() {
        return new Status(
                status.online(),
                worker.pendingCount(),
                worker.oldestPendingAt(),
                status.lastSuccessAt(),
                status.lastAttemptAt(),
                status.lastError());
    }

    /**
     * @param online the last attempt succeeded — not merely that a cable is plugged in
     * @param pending unsynced rows sitting in the outbox
     * @param oldestPendingAt when the oldest of them was written. A count alone cannot distinguish
     *     "busy minute" from "offline since Tuesday".
     */
    public record Status(
            boolean online,
            int pending,
            Instant oldestPendingAt,
            Instant lastSuccessAt,
            Instant lastAttemptAt,
            String lastError) {}
}

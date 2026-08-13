package com.lumora.pos.sync;

import java.util.List;
import java.util.UUID;

/**
 * Per-item outcome, not a single verdict for the batch.
 *
 * <p>One malformed aggregate must not strand the ninety-nine good ones behind it: the drain acks
 * what was accepted and leaves the rest to retry or to be looked at. A batch that either wholly
 * succeeds or wholly fails turns one bad row into a permanently stuck till.
 *
 * @param accepted ids the cloud has durably stored — safe for the shop to ack
 * @param rejected ids that will never succeed unchanged, with the reason
 */
public record SyncBatchResult(List<UUID> accepted, List<Rejection> rejected) {

    public record Rejection(UUID aggregateId, String reason) {}
}

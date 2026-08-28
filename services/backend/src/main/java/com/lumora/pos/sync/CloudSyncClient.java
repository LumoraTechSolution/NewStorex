package com.lumora.pos.sync;

/**
 * The only thing in the shop allowed to touch the network on a sale's behalf — and it does so long
 * after the sale is already final.
 *
 * <p>An interface because the offline tests need to make it fail on demand. "What happens when this
 * throws" is the behaviour under test, not an edge case.
 */
public interface CloudSyncClient {

    /**
     * @throws CloudUnreachableException when the batch may succeed if sent again — no connection,
     *     timeout, 5xx. Anything that will never succeed unchanged comes back as a rejection inside
     *     the result instead.
     */
    SyncBatchResult push(SyncBatch batch);

    /**
     * The downward half (M4-09): what the cloud says this shop is licensed and entitled to.
     *
     * <p>On this interface rather than on a client of its own because it rides the same tick, the
     * same base URL and the same credential as the push. A second HTTP component would be a second
     * place to configure a timeout and a second thing to forget when the token changes.
     *
     * @throws CloudUnreachableException for everything that might work later. There is no
     *     per-item outcome to report here — the answer either arrived or it did not — and a failure
     *     costs the till nothing, because the last answer is still cached and still governs.
     */
    Entitlement fetchEntitlement();

    class CloudUnreachableException extends RuntimeException {
        public CloudUnreachableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

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

    class CloudUnreachableException extends RuntimeException {
        public CloudUnreachableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

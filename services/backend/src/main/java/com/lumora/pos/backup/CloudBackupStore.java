package com.lumora.pos.backup;

import java.io.InputStream;

/**
 * Where the bytes of an uploaded archive actually land (M5-06).
 *
 * <p>An interface for the same reason {@code CloudSyncClient} is one: the interesting behaviour is
 * what happens when it fails, and a test needs to be able to cause that. It also keeps the choice
 * of storage out of {@link CloudBackupService}, which is the part that must not change when a
 * client insists on their own AWS account.
 *
 * <p>The key is opaque here and built by the service. Implementations must treat it as a name, not
 * as a path to resolve against anything a caller supplied — see {@link CloudBackupService} for the
 * validation that makes that safe.
 */
public interface CloudBackupStore {

    /**
     * Writes {@code contentLength} bytes, replacing anything already under {@code key}.
     *
     * <p>Replacing rather than refusing is deliberate. The key is derived from the shop, the till
     * and the archive's own name, so the only thing that can collide is the same archive arriving
     * twice — which is what a retry after a timeout looks like, and it must be safe.
     *
     * @throws java.io.UncheckedIOException when the write failed and may work later. The till still
     *     holds the archive and will offer it again; nothing is lost by failing here.
     */
    void put(String key, InputStream body, long contentLength);

    /** Removes an object. Missing is success — retention must be safe to run twice. */
    void delete(String key);

    /** One line for the startup log, so an operator can see which store is live without guessing. */
    String describe();
}

package com.lumora.pos.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The store for a host with a real disk — a laptop, the test suite, a client who insists their
 * data never leaves their own server.
 *
 * <p><b>Not for the deployed free tier.</b> Render's container filesystem is recreated on every
 * deploy, so archives written here would vanish at the next release and nobody would find out until
 * a shop needed one. {@code CloudBackupService} logs which store is live at startup precisely so
 * that mistake is visible rather than silent.
 *
 * <p>Writes to {@code .part} and renames, the same discipline the till uses in {@code backup.cjs}:
 * an interrupted write must not leave something that looks like a good archive, because a truncated
 * backup is worse than a missing one — it is the one somebody restores from.
 */
public class FilesystemBackupStore implements CloudBackupStore {

    private final Path root;

    public FilesystemBackupStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, InputStream body, long contentLength) {
        Path target = resolve(key);
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            Files.copy(body, partial, StandardCopyOption.REPLACE_EXISTING);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
                // Nothing useful to do, and the original failure is the one worth reporting.
            }
            throw new UncheckedIOException("Could not write " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + key, e);
        }
    }

    @Override
    public String describe() {
        return "filesystem at " + root;
    }

    /**
     * Resolves a key under the root and refuses anything that escapes it.
     *
     * <p>The service validates every component of the key before building it, so this cannot
     * trigger from a correct call. It is here because that is exactly the kind of guarantee that
     * survives until somebody adds a second caller, and the failure it prevents is writing a
     * shop's upload over a file somewhere else on the host.
     */
    private Path resolve(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Backup key escapes the store root: " + key);
        }
        return resolved;
    }
}

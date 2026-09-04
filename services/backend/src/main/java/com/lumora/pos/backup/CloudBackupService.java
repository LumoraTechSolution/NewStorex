package com.lumora.pos.backup;

import com.lumora.pos.web.RejectedException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Takes a shop's whole database off the shop's disk (M5-06, resolves D5).
 *
 * <h2>What this is for, given eleven aggregates already sync</h2>
 *
 * The outbox carries what the cloud needs in order to <em>report</em>. It is not a second copy:
 * {@code invoice_counters} never leaves the till, so a shop rebuilt from synced aggregates alone
 * would re-issue invoice numbers already printed on paper in a customer's hand. {@code
 * docs/restore.md} says so at length. What arrives here is {@code pg_dump}'s own output — the whole
 * database, restorable with {@code pg_restore}, counters included.
 *
 * <h2>The order of writes is the only interesting decision in this class</h2>
 *
 * The object is written first and the row second; retention deletes the row first and the object
 * second. Both follow one rule: <b>there is never a row without its object.</b> An orphaned object
 * costs a fraction of a cent and is invisible. A row pointing at nothing is a lie told to whoever
 * is standing in a shop at 8am with a dead PC, and they will believe it right up until the
 * download fails.
 *
 * <h2>Nothing here trusts the till's headers</h2>
 *
 * The name, the terminal code and the digest all arrive from a machine on a shop floor, and the
 * name would become part of a path on our storage. Every one is matched against a pattern before
 * it is used, the key is built here rather than accepted, and the digest is recomputed from the
 * bytes that actually arrived. A mismatch deletes what was written: an archive nobody can vouch
 * for is exactly the archive somebody would restore from.
 */
@Service
@Profile("cloud")
public class CloudBackupService {

    /**
     * The till's own naming, from {@code backup.cjs}: {@code storex-2026-09-01T0314.dump}.
     *
     * <p>Anchored, and deliberately narrow. This string becomes part of an object key, and the
     * cheap version of the check — "reject anything containing a dot-dot" — is the one that gets
     * defeated by an encoding nobody thought about. An allowlist of a single shape cannot be.
     */
    private static final Pattern NAME =
            Pattern.compile("^storex-\\d{4}-\\d{2}-\\d{2}T\\d{4}\\.dump$");

    private static final Pattern TERMINAL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private static final Logger log = LoggerFactory.getLogger(CloudBackupService.class);

    private final JdbcTemplate jdbc;
    private final CloudBackupStore store;
    private final CloudBackupProperties properties;

    public CloudBackupService(
            JdbcTemplate jdbc, CloudBackupStore store, CloudBackupProperties properties) {
        this.jdbc = jdbc;
        this.store = store;
        this.properties = properties;
        log.info(
                "Cloud backups: {}, keeping {} per shop",
                store.describe(),
                properties.keepPerTenant());
    }

    /**
     * What the till is told about an archive it just sent.
     *
     * @param alreadyHeld true when this exact archive was already here. Neither an error nor
     *     silence: a till that retried after a timeout needs to know it may stop, and a till
     *     re-sending the same file every twelve hours because of a bug is something somebody has
     *     to be able to see in a log.
     */
    public record Accepted(String name, long bytes, String sha256, boolean alreadyHeld) {}

    /**
     * Streams one archive into storage and records it.
     *
     * <p>Deliberately not {@code @Transactional}. The upload is seconds to minutes of a shop's
     * connection, and holding a database transaction open across it would park one connection of
     * five while a till in Kandy finishes sending. The database work is a single statement at the
     * end, by which time the bytes are already safe.
     */
    public Accepted accept(
            long tenantId,
            String terminalCode,
            String name,
            Instant takenAt,
            String declaredSha256,
            long contentLength,
            InputStream body) {

        if (!NAME.matcher(name).matches()) {
            throw new RejectedException("Not a StoreX backup name: " + name);
        }
        if (!TERMINAL.matcher(terminalCode).matches()) {
            throw new RejectedException("Not a terminal code: " + terminalCode);
        }
        if (declaredSha256 == null || !SHA256.matcher(declaredSha256).matches()) {
            throw new RejectedException("Not a SHA-256 digest: " + declaredSha256);
        }
        if (contentLength <= 0) {
            // Chunked, or a header nobody set. Both mean we cannot tell in advance how much of
            // somebody else's storage this request intends to use, which is the one thing the cap
            // below exists to know.
            throw new RejectedException("A backup upload must declare its Content-Length");
        }
        if (contentLength > properties.maxBytes()) {
            throw new RejectedException(
                    "Backup is "
                            + contentLength
                            + " bytes; the limit is "
                            + properties.maxBytes());
        }

        String key = "tenant-%d/%s/%s".formatted(tenantId, terminalCode, name);

        MessageDigest digest = sha256();
        Counting counted = new Counting(new DigestInputStream(body, digest));
        store.put(key, counted, contentLength);

        String actual = HexFormat.of().formatHex(digest.digest());
        if (counted.count != contentLength || !actual.equals(declaredSha256)) {
            // Written, unvouchable, and now gone. Keeping it would leave storage holding an
            // archive of unknown contents listed beside ones whose contents are known.
            store.delete(key);
            throw new RejectedException(
                    "The archive that arrived is not the one that was sent (%d of %d bytes, digest %s)"
                            .formatted(
                                    counted.count,
                                    contentLength,
                                    actual.equals(declaredSha256) ? "matched" : "differed"));
        }

        int inserted =
                jdbc.update(
                        """
                        INSERT INTO tenant_backups
                            (tenant_id, terminal_code, name, taken_at, bytes, sha256, object_key)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, terminal_code, name) DO NOTHING
                        """,
                        tenantId,
                        terminalCode,
                        name,
                        java.sql.Timestamp.from(takenAt),
                        contentLength,
                        actual,
                        key);

        prune(tenantId);
        return new Accepted(name, contentLength, actual, inserted == 0);
    }

    /**
     * Keeps the newest {@link CloudBackupProperties#keepPerTenant()} archives for one shop.
     *
     * <p>Failures are logged and swallowed. A shop whose retention failed is holding <em>more</em>
     * copies of its own history than it is entitled to, which is a storage bill and not a data
     * loss — and failing the upload over it would turn that bill into a shop with no off-site
     * copy at all.
     */
    private void prune(long tenantId) {
        try {
            List<String> expired =
                    jdbc.queryForList(
                            """
                            SELECT object_key FROM tenant_backups
                             WHERE tenant_id = ?
                             ORDER BY taken_at DESC, id DESC
                            OFFSET ?
                            """,
                            String.class,
                            tenantId,
                            properties.keepPerTenant());
            for (String key : expired) {
                // Row first, then object: see the class note. The window this opens is an object
                // nobody references; the window the other order opens is a row promising an
                // archive that is not there.
                jdbc.update(
                        "DELETE FROM tenant_backups WHERE tenant_id = ? AND object_key = ?",
                        tenantId,
                        key);
                store.delete(key);
            }
            if (!expired.isEmpty()) {
                log.info(
                        "Cloud backups: removed {} expired archives for tenant {}",
                        expired.size(),
                        tenantId);
            }
        } catch (RuntimeException e) {
            log.warn("Cloud backups: could not prune tenant {}: {}", tenantId, e.toString());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not optional on any JRE this runs on", e);
        }
    }

    /** Counts what the store actually consumed, so a short body cannot pass as a whole archive. */
    private static final class Counting extends FilterInputStream {
        private long count;

        private Counting(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) count += read;
            return read;
        }
    }
}

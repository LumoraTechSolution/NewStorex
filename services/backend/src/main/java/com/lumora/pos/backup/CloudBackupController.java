package com.lumora.pos.backup;

import com.lumora.pos.cloud.AuthenticatedPrincipal;
import com.lumora.pos.cloud.CloudPrincipals;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a till hands over its {@code pg_dump} (M5-06).
 *
 * <p>Under {@code /api/sync} with the batch and the entitlement, because it is the same
 * conversation with the same credential and the same base URL — a till that can reach one can
 * reach all three, and an operator debugging a shop looks in one place.
 *
 * <h2>Raw bytes, not multipart</h2>
 *
 * A multipart body would be parsed, and Spring's default parsing writes the part to a temporary
 * file first — which on a 512 MB host with an ephemeral disk means the archive lands twice, once
 * where nobody wanted it. The body <em>is</em> the archive; everything about it that a form field
 * would have carried is a header instead.
 *
 * <h2>A till credential, and only a till</h2>
 *
 * An owner's console session must never reach this. The console is read-only by construction and
 * this writes a shop's entire database into storage — a distinction that would be invisible if
 * both kinds resolved to the same tenant, which is exactly why {@code AuthenticatedPrincipal}
 * carries the kind.
 */
@RestController
@RequestMapping("/api/sync")
@Profile("cloud")
public class CloudBackupController {

    private final CloudBackupService backups;

    public CloudBackupController(CloudBackupService backups) {
        this.backups = backups;
    }

    /**
     * @param name the archive's own file name on the shop PC. Validated hard downstream — it
     *     becomes part of an object key.
     * @param takenAt when the shop took the dump, ISO-8601. Its own clock, which may be wrong;
     *     {@code received_at} is ours and is not.
     * @param sha256 what the till says the bytes hash to. Recomputed here from what arrived, and
     *     the upload is rejected if the two differ.
     */
    @PostMapping(value = "/backup", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public CloudBackupService.Accepted upload(
            @RequestHeader("X-Backup-Terminal") String terminalCode,
            @RequestHeader("X-Backup-Name") String name,
            @RequestHeader("X-Backup-Taken-At") String takenAt,
            @RequestHeader("X-Backup-Sha256") String sha256,
            HttpServletRequest request) {

        long tenantId =
                CloudPrincipals.require(request, AuthenticatedPrincipal.Kind.TILL).tenantId();

        try {
            return backups.accept(
                    tenantId,
                    terminalCode,
                    name,
                    parse(takenAt),
                    sha256,
                    request.getContentLengthLong(),
                    request.getInputStream());
        } catch (IOException e) {
            // The connection died mid-upload. A 500 rather than a 422: the till holds the archive,
            // this may well work on the next attempt, and telling it to stop trying would leave a
            // shop with no off-site copy over one dropped connection.
            throw new UncheckedIOException("The upload did not finish", e);
        }
    }

    private Instant parse(String takenAt) {
        try {
            return Instant.parse(takenAt);
        } catch (DateTimeParseException e) {
            throw new com.lumora.pos.web.RejectedException(
                    "X-Backup-Taken-At is not an ISO-8601 instant: " + takenAt);
        }
    }
}

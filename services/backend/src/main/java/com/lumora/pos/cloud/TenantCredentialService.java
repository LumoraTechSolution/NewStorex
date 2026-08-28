package com.lumora.pos.cloud;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints and checks the bearer tokens a till presents to the cloud (M4-01).
 *
 * <p>This is the cloud's answer to the question {@code LocalShop} answers on the shop PC: <em>who
 * is this?</em> On a till it is settled by the database holding exactly one tenant. Here it can
 * only come from the credential on the request, because the whole point of the cloud is that many
 * shops reach the same port.
 *
 * <p>The plaintext token exists for exactly one moment — inside {@link #provision} — and is never
 * written down. Everything afterwards works from the hash.
 */
@Service
@Profile("cloud")
public class TenantCredentialService {

    /** 256 bits. Long enough that guessing is not a threat model anyone has to reason about. */
    private static final int TOKEN_BYTES = 32;

    /**
     * Prefixed so a leaked token is recognisable as one. A key that looks like anonymous base64 is
     * a key that gets pasted into an issue; a key that says {@code lum_} can be grepped for in a
     * repository scan and revoked before it is used.
     */
    private static final String TOKEN_PREFIX = "lum_";

    /** How much of the token is kept in clear for identification. */
    private static final int STORED_PREFIX_LENGTH = TOKEN_PREFIX.length() + 6;

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public TenantCredentialService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The tenant a token belongs to, or empty if it does not authenticate one.
     *
     * <p>Revoked credentials, suspended tenants and — since M4-08 — lapsed licences are all empty
     * rather than distinct outcomes. The caller turns every one of them into the same 401, because
     * telling an unauthenticated caller <em>which</em> it hit tells them the others exist.
     *
     * <p><b>The licence predicate is what makes a plan mean anything</b> (M4-08). A shop whose
     * licence has run out stops reaching the cloud; it does not stop selling, because the sale was
     * never on the network's critical path, and it does not stop its owner reading the console —
     * V209's header has the argument for that asymmetry. The outbox simply queues, and the moment a
     * renewal is granted the backlog drains on the next tick with nothing lost.
     */
    @Transactional(readOnly = true)
    public Optional<AuthenticatedTenant> authenticate(String presentedToken) {
        return lookup(presentedToken, true);
    }

    /**
     * The same credential check with the licence predicate dropped (M4-09).
     *
     * <p>It exists for exactly one endpoint — the entitlement feed — and {@link TenantAuthFilter}
     * names that endpoint in an allowlist of exact paths rather than letting anything opt in. The
     * reason it has to exist at all is a circularity in the strict version above: a lapsed shop
     * cannot authenticate, so if the only way to learn "your licence expired on the 14th" were an
     * authenticated call, the shop would learn it only while it was still licensed. The till would
     * see nothing but 401s and be unable to say why, which is precisely the support call this is
     * supposed to prevent.
     *
     * <p>Everything else the strict check enforces still applies here: a revoked credential and a
     * suspended tenant are still nobody. Only the licence window is relaxed, and the endpoint it is
     * relaxed for returns the licence window itself and touches nothing else.
     */
    @Transactional(readOnly = true)
    public Optional<AuthenticatedTenant> authenticateEvenIfLapsed(String presentedToken) {
        return lookup(presentedToken, false);
    }

    private Optional<AuthenticatedTenant> lookup(String presentedToken, boolean requireLicence) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    jdbc.queryForObject(
                            """
                            SELECT c.id, c.tenant_id, t.client_uuid
                              FROM tenant_api_credentials c
                              JOIN tenants t ON t.id = c.tenant_id
                             WHERE c.token_hash = ?
                               AND c.revoked_at IS NULL
                               AND t.active
                               AND (NOT ? OR EXISTS (
                                     SELECT 1 FROM tenant_licences l
                                      WHERE l.tenant_id = t.id
                                        AND l.starts_at <= now()
                                        AND l.expires_at > now()))
                            """,
                            (rs, row) ->
                                    new AuthenticatedTenant(
                                            rs.getLong("id"),
                                            rs.getLong("tenant_id"),
                                            rs.getObject("client_uuid", UUID.class)),
                            hash(presentedToken),
                            requireLicence));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Records that a credential was just used. Separate from {@link #authenticate} so the read
     * path stays a read: this is one write per batch, not per aggregate, and a failure to record it
     * must never fail the ingest it was describing.
     */
    public void touch(long credentialId) {
        jdbc.update("UPDATE tenant_api_credentials SET last_seen_at = now() WHERE id = ?", credentialId);
    }

    /**
     * Creates a tenant, a trial licence and a first credential.
     *
     * <p>The low-level path, and no longer the one a person uses: M4-08's {@link TenantAdminService}
     * is what a screen calls, because a real shop also needs an owner who can sign in and an audit
     * row saying who created it. This remains for the bootstrap and for tests, which want a working
     * tenant in one line and have no admin to attribute it to.
     *
     * <p>The trial licence is not optional. Since M4-08 the token's own authentication requires a
     * licence covering now, so a tenant provisioned without one would hold a credential that
     * authenticates as nothing — a shop that looks created and silently syncs nothing.
     *
     * @return the new tenant, and the only copy of its token there will ever be
     */
    @Transactional
    public Provisioned provision(String tenantName, String credentialLabel) {
        UUID clientUuid = UUID.randomUUID();
        Long tenantId =
                jdbc.queryForObject(
                        "INSERT INTO tenants (client_uuid, name) VALUES (?, ?) RETURNING id",
                        Long.class,
                        clientUuid,
                        tenantName);

        // granted_by stays null: nobody authorised this, the system did. V209 says so in a column
        // comment rather than leaving a dangling reference to an admin that may not exist.
        jdbc.update(
                """
                INSERT INTO tenant_licences (tenant_id, plan_id, expires_at, note)
                SELECT ?, p.id, now() + make_interval(days => ?), 'Provisioned with the tenant.'
                  FROM plans p WHERE p.code = ?
                """,
                tenantId,
                LicenceService.DEFAULT_TRIAL_DAYS,
                LicenceService.DEFAULT_PLAN_CODE);

        return new Provisioned(tenantId, clientUuid, issueToken(tenantId, credentialLabel));
    }

    /**
     * Adds a credential to a tenant that already exists — a second till, or a replacement for one
     * that was lost. Returns the plaintext exactly once.
     */
    @Transactional
    public String issueToken(long tenantId, String label) {
        byte[] entropy = new byte[TOKEN_BYTES];
        random.nextBytes(entropy);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);

        jdbc.update(
                """
                INSERT INTO tenant_api_credentials (tenant_id, label, token_prefix, token_hash)
                VALUES (?, ?, ?, ?)
                """,
                tenantId,
                label,
                token.substring(0, STORED_PREFIX_LENGTH),
                hash(token));

        return token;
    }

    /** Cuts off one machine without touching the others, or the shop's history. */
    @Transactional
    public void revoke(long credentialId) {
        jdbc.update(
                "UPDATE tenant_api_credentials SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL",
                credentialId);
    }

    /**
     * Hex SHA-256. Deterministic on purpose — see V205 for why this is not BCrypt, which would turn
     * every batch's one index probe into a scan with a key-stretch on each row.
     */
    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * @param credentialId which key was used — the thing to revoke, and the thing to name in a log
     * @param tenantId the only tenant this request is allowed to touch
     */
    public record AuthenticatedTenant(long credentialId, long tenantId, UUID tenantClientUuid) {}

    /** @param token the plaintext, which is not recoverable after this record is discarded */
    public record Provisioned(long tenantId, UUID tenantClientUuid, String token) {}
}

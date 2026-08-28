package com.lumora.pos.auth;

import com.lumora.pos.user.Permission;
import com.lumora.pos.user.Role;
import com.lumora.pos.user.UserService;
import com.lumora.pos.user.UserService.Operator;
import com.lumora.pos.web.RejectedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign in once, then carry a token (M3-09).
 *
 * <h2>What this replaces</h2>
 *
 * M3-08 shipped {@code OperatorGate}: the back office kept the operator's code and PIN in memory
 * and replayed both on every request. It was labelled as the interim it was. This is the thing it
 * was interim to — the PIN is sent exactly once, exchanged for a token, and never held again.
 *
 * <h2>Nothing here can reach the network</h2>
 *
 * The PIN hash is in this database (V109), the signing key is in this database (V115), and the
 * session row is in this database. A till with its cable pulled out signs people in exactly as
 * fast as one that is online, because the two paths are the same path. That is the requirement,
 * and it is met by there being no remote call to omit — not by a fallback that might be wrong.
 *
 * <h2>The token proves a PIN was passed; the row decides whether that still holds</h2>
 *
 * The JWT carries only who and which session — {@code sub} and {@code jti}. Not the role, not the
 * permissions. Claims are a snapshot taken at sign-in, and a snapshot of a permission is a
 * permission that keeps working after it is taken away: deactivate a user mid-shift, or demote
 * one, and a self-contained token would go on letting them through until it expired. So every
 * verification re-reads the user, and the role that answers a permission check is the role in the
 * database right now.
 *
 * <p>The obvious objection is that this makes the token little more than a signed session id.
 * That is accurate, and it is what is wanted here: the issuer and the verifier are one process
 * talking to a Postgres on the same machine, so statelessness buys nothing and costs revocation.
 */
@Service
public class SessionService {

    /**
     * Fifteen minutes, refreshed while somebody is working.
     *
     * <p>Short because the token is the credential, and this is the window in which a copied one
     * is worth anything. Refresh means a person doing back-office work never meets the limit;
     * walking away and coming back after lunch means signing in again, which is the correct
     * outcome for an unattended screen in a shop.
     */
    private final Duration ttl;

    private final JdbcTemplate jdbc;
    private final UserService users;
    private final SigningKeyStore keys;

    public SessionService(
            JdbcTemplate jdbc,
            UserService users,
            SigningKeyStore keys,
            @Value("${lumora.auth.session-ttl:PT15M}") Duration ttl) {
        this.jdbc = jdbc;
        this.users = users;
        this.keys = keys;
        this.ttl = ttl;
    }

    /** A signed-in operator and the token that says so. */
    public record Session(String token, Instant expiresAt, Operator operator) {}

    // ------------------------------------------------------------------------- opening

    /**
     * Authenticates a code and PIN and opens a session.
     *
     * <p>Takes the permission the session is being opened <em>for</em>, so a sign-in that would be
     * useless fails at the door. Signing a cashier in to the back office and then refusing every
     * screen they touch tells them less, later, and leaves a live session behind for an account
     * that should never have had one.
     */
    @Transactional
    public Session open(long tenantId, String code, String pin, String surface, Permission entry) {
        Operator operator = users.authorise(tenantId, code, pin, entry);

        UUID jti = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);

        jdbc.update(
                """
                INSERT INTO sessions (jti, tenant_id, user_id, surface, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                jti,
                tenantId,
                operator.id(),
                surface,
                utc(now),
                utc(expiresAt));

        return new Session(mint(jti, operator.id(), now, expiresAt), expiresAt, operator);
    }

    // -------------------------------------------------------------------- verification

    /**
     * Verifies a bearer token and checks one permission, or throws.
     *
     * <p>Returns the {@link Operator} for the same reason {@code UserService.authorise} does:
     * every gated action has an audit column to fill in, and handing back who passed the gate
     * means the caller cannot write down somebody else.
     */
    @Transactional
    public Operator require(String bearer, Permission permission) {
        Operator operator = verify(bearer);
        if (!operator.can(permission)) {
            throw new RejectedException(
                    operator.displayName()
                            + " is a "
                            + operator.role().name().toLowerCase(Locale.ROOT)
                            + " and cannot "
                            + permission.describe()
                            + ". Ask someone with a higher role to authorise this.");
        }
        return operator;
    }

    /** Verifies a bearer token without asking what for. */
    @Transactional
    public Operator verify(String bearer) {
        UUID jti = parse(bearer);

        List<Live> live =
                jdbc.query(
                        """
                        SELECT s.id, u.id AS user_id, u.code, u.display_name, u.role,
                               s.expires_at, u.active
                          FROM sessions s JOIN users u ON u.id = s.user_id
                         WHERE s.jti = ? AND s.revoked_at IS NULL
                        """,
                        (rs, row) ->
                                new Live(
                                        rs.getLong("id"),
                                        rs.getLong("user_id"),
                                        rs.getString("code"),
                                        rs.getString("display_name"),
                                        rs.getString("role"),
                                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                                        rs.getBoolean("active")),
                        jti);

        if (live.isEmpty()) {
            throw expired();
        }
        Live session = live.get(0);
        if (!session.userActive()) {
            // Belt and braces: deactivating a user revokes their sessions, so reaching here means
            // a row was changed by hand. Refuse anyway — `active = false` is the shop saying this
            // person may not act, and that should not depend on another statement having run.
            throw new RejectedException("That account is no longer active. Sign in again.");
        }
        if (!session.expiresAt().isAfter(Instant.now())) {
            throw expired();
        }

        jdbc.update("UPDATE sessions SET last_seen_at = now() WHERE id = ?", session.id());
        return new Operator(
                session.userId(), session.code(), session.displayName(), Role.of(session.role()));
    }

    /**
     * Extends a live session and mints a token for the rest of it.
     *
     * <p>Same {@code jti}: this is the same sign-in, and a new row per refresh would turn an
     * afternoon's work into forty rows that all mean "this person is still here". Refreshing
     * re-runs {@link #verify}, so a session revoked or a user deactivated a second ago cannot
     * refresh its way back to life.
     */
    @Transactional
    public Session refresh(String bearer) {
        Operator operator = verify(bearer);
        UUID jti = parse(bearer);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);

        jdbc.update(
                "UPDATE sessions SET expires_at = ?, last_seen_at = now() WHERE jti = ?",
                utc(expiresAt),
                jti);
        return new Session(mint(jti, operator.id(), now, expiresAt), expiresAt, operator);
    }

    // ------------------------------------------------------------------------ revoking

    /** Signs out. Idempotent — pressing it twice, or on a token already dead, is not an error. */
    @Transactional
    public void revoke(String bearer, String why) {
        UUID jti;
        try {
            jti = parse(bearer);
        } catch (RejectedException unreadable) {
            // Signing out with a token this till cannot read is already the desired state.
            return;
        }
        jdbc.update(
                """
                UPDATE sessions SET revoked_at = now(), revoked_why = ?
                 WHERE jti = ? AND revoked_at IS NULL
                """,
                why,
                jti);
    }

    /**
     * Ends every session a user holds. Run when they are deactivated, when their role changes and
     * when their PIN is reset — the three moments at which a session opened under the old facts is
     * precisely what has to stop working.
     */
    @Transactional
    public int revokeAllFor(long userId, String why) {
        return jdbc.update(
                """
                UPDATE sessions SET revoked_at = now(), revoked_why = ?
                 WHERE user_id = ? AND revoked_at IS NULL
                """,
                why,
                userId);
    }

    // -------------------------------------------------------------------------- tokens

    private String mint(UUID jti, long userId, Instant issuedAt, Instant expiresAt) {
        SigningKeyStore.Key key = keys.active();
        return Jwts.builder()
                .header()
                .keyId(key.kid())
                .and()
                .id(jti.toString())
                .subject(Long.toString(userId))
                .issuer(ISSUER)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key.material(), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Reads the {@code jti} out of a bearer token, or throws.
     *
     * <p>The algorithm is pinned twice over. Parsing through a key locator refuses {@code alg:
     * none} outright, and the check below refuses anything that is not HS256 — including the
     * classic confusion where an RS256 token is crafted so a public key gets used as an HMAC
     * secret. That attack needs a key this till never hands out, so the check guards against a
     * future mistake rather than a present adversary, which is exactly the kind worth keeping.
     */
    private UUID parse(String bearer) {
        String token = stripBearer(bearer);
        try {
            Jws<Claims> jws =
                    Jwts.parser()
                            .keyLocator(locator())
                            .requireIssuer(ISSUER)
                            .build()
                            .parseSignedClaims(token);
            if (!Jwts.SIG.HS256.getId().equals(jws.getHeader().getAlgorithm())) {
                throw new RejectedException("That sign-in is not valid on this till.");
            }
            return UUID.fromString(jws.getPayload().getId());
        } catch (RejectedException rethrow) {
            throw rethrow;
        } catch (ExpiredJwtException e) {
            throw expired();
        } catch (RuntimeException e) {
            // A bad signature, an unknown kid, a malformed token, the wrong issuer, a jti that is
            // not a uuid — all one message on purpose. Telling a caller which part of a token it
            // got wrong is telling it how to get the next one right.
            throw new RejectedException("That sign-in is not valid on this till.");
        }
    }

    /**
     * Looks up the key a token names. An unknown kid returns null and parsing then fails.
     *
     * <p>A method and not a field: a field initialiser capturing {@code keys} runs before the
     * constructor assigns it, which the compiler catches here and would not in a language that
     * let it through.
     */
    private Locator<Key> locator() {
        return header -> {
            Object kid = header.get("kid");
            SigningKeyStore.Key key = keys.byKid(kid == null ? null : kid.toString());
            return key == null ? null : key.material();
        };
    }

    private static String stripBearer(String header) {
        if (header == null || header.isBlank()) {
            throw new RejectedException("Sign in to the back office first.");
        }
        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    private static OffsetDateTime utc(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }

    private static RejectedException expired() {
        return new RejectedException("That sign-in has expired. Sign in again.");
    }

    /**
     * Names the machine, not the company. A token minted by one till must not verify on another —
     * the key already ensures that, and this is what makes the refusal legible when it happens.
     */
    private static final String ISSUER = "lumora-till";

    private record Live(
            long id,
            long userId,
            String code,
            String displayName,
            String role,
            Instant expiresAt,
            boolean userActive) {}
}

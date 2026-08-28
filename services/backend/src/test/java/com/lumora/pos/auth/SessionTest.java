package com.lumora.pos.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.auth.SessionService.Session;
import com.lumora.pos.testfixtures.ShopFixture;
import com.lumora.pos.user.Permission;
import com.lumora.pos.user.Role;
import com.lumora.pos.user.UserService;
import com.lumora.pos.user.UserService.Operator;
import com.lumora.pos.user.UserService.UserRow;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Offline sessions (M3-09).
 *
 * <p>What is being tested is not "a JWT round-trips" — that is JJWT's test suite, not this one.
 * It is the two decisions M3-09 actually made: that the token is worthless without a live session
 * row, and that everything the row is checked against lives on this machine. Every case below
 * would pass with the network cable out, because nothing in the path reaches for it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class SessionTest {

    @Autowired SessionService sessions;
    @Autowired SigningKeyStore keys;
    @Autowired UserService users;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    private static final String SURFACE = "BACK_OFFICE";

    // ------------------------------------------------------------------ the happy path

    @Test
    void aPinIsExchangedForATokenThatIdentifiesTheSamePerson() {
        ShopFixture.Shop shop = fixtures.seed();
        Actor manager = newActor(shop, Role.MANAGER);

        Session session = open(shop, manager);

        assertThat(session.operator().id()).isEqualTo(manager.id());
        assertThat(session.expiresAt()).isAfter(java.time.Instant.now());

        Operator carried = sessions.require("Bearer " + session.token(), Permission.BACK_OFFICE);
        assertThat(carried.id()).isEqualTo(manager.id());
        assertThat(carried.role()).isEqualTo(Role.MANAGER);
    }

    /**
     * The PIN is not recoverable from anything the sign-in produces.
     *
     * <p>The whole reason M3-09 exists is that {@code OperatorGate} put the PIN on every request.
     * A token that embedded it — even encoded, even base64 — would have moved the problem rather
     * than solved it, and base64 is exactly the kind of thing that looks like encryption at a
     * glance during review.
     */
    @Test
    void theTokenDoesNotCarryThePin() {
        ShopFixture.Shop shop = fixtures.seed();

        String token = open(shop, newActor(shop, Role.MANAGER)).token();
        String decoded = decodeSegments(token);

        assertThat(decoded).doesNotContain(PIN);
        assertThat(token).doesNotContain(PIN);
        assertThat(token)
                .doesNotContain(
                        Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(PIN.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The role is not in the token either.
     *
     * <p>A claim is a snapshot, and a snapshot of a permission keeps working after the permission
     * is taken away. Asserting its absence is what stops somebody adding it later as a harmless
     * optimisation that skips a query — and quietly turning revocation into a fifteen-minute delay.
     */
    @Test
    void theTokenDoesNotCarryTheRole() {
        ShopFixture.Shop shop = fixtures.seed();

        String decoded = decodeSegments(open(shop, newActor(shop, Role.MANAGER)).token());

        assertThat(decoded).doesNotContain("MANAGER");
        assertThat(decoded).doesNotContain("BACK_OFFICE");
    }

    /** A sign-in that could never be used is refused at the door rather than issued and ignored. */
    @Test
    void aCashierCannotOpenABackOfficeSession() {
        ShopFixture.Shop shop = fixtures.seed();
        Actor cashier = newActor(shop, Role.CASHIER);

        assertThatThrownBy(
                        () ->
                                sessions.open(
                                        shop.tenantId(),
                                        cashier.code(),
                                        PIN,
                                        SURFACE,
                                        Permission.BACK_OFFICE))
                .hasMessageContaining("cannot open the back office");

        assertThat(sessionCountFor(cashier.id())).isZero();
    }

    @Test
    void aWrongPinOpensNoSession() {
        ShopFixture.Shop shop = fixtures.seed();
        Actor manager = newActor(shop, Role.MANAGER);

        assertThatThrownBy(
                        () ->
                                sessions.open(
                                        shop.tenantId(),
                                        manager.code(),
                                        "0000",
                                        SURFACE,
                                        Permission.BACK_OFFICE))
                .hasMessageContaining("not recognised");

        assertThat(sessionCountFor(manager.id())).isZero();
    }

    // ----------------------------------------------------------------- what the row decides

    /** Signing out means it: the token dies now, not at its expiry. */
    @Test
    void signingOutStopsTheTokenImmediately() {
        ShopFixture.Shop shop = fixtures.seed();
        Session session = open(shop, newActor(shop, Role.MANAGER));

        sessions.revoke(session.token(), "signed out");

        assertThatThrownBy(() -> sessions.verify(session.token())).hasMessageContaining("expired");
    }

    /** Pressing sign out twice, or on a token that is already dead, is not an error. */
    @Test
    void signingOutIsIdempotent() {
        ShopFixture.Shop shop = fixtures.seed();
        Actor manager = newActor(shop, Role.MANAGER);
        Session session = open(shop, manager);

        sessions.revoke(session.token(), "signed out");
        sessions.revoke(session.token(), "signed out again");
        sessions.revoke("not a token at all", "signed out");

        // The second revoke must not overwrite the first. When a session ended, and why, is the
        // question the row exists to answer, and a last-writer-wins update answers it wrongly.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revoked_why FROM sessions WHERE user_id = ?",
                                String.class,
                                manager.id()))
                .isEqualTo("signed out");
    }

    /**
     * A session past its expiry is refused even though the row is still there and unrevoked.
     *
     * <p>The row is aged rather than the clock moved: the expiry that matters is the database's,
     * and a test that only moved the JWT's would pass while the authoritative check was missing.
     */
    @Test
    void anExpiredSessionIsRefused() {
        ShopFixture.Shop shop = fixtures.seed();
        Actor manager = newActor(shop, Role.MANAGER);
        Session session = open(shop, manager);

        jdbc.update(
                "UPDATE sessions SET expires_at = ? WHERE user_id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                manager.id());

        assertThatThrownBy(() -> sessions.verify(session.token())).hasMessageContaining("expired");
    }

    /**
     * Deactivating a user ends what they were doing.
     *
     * <p>Both halves are asserted: the sessions are revoked, and verification refuses even a row
     * left live by hand. The moment a shop most wants {@code active = false} to bite is the moment
     * somebody is being walked off the floor, and one mechanism is one thing to get wrong.
     */
    @Test
    void deactivatingAUserEndsTheirSessions() {
        ShopFixture.Shop shop = fixtures.seed();
        Actor leaver = newActor(shop, Role.MANAGER);
        Session session = open(shop, leaver);

        users.setActive(shop.tenantId(), leaver.id(), false);
        assertThat(sessions.revokeAllFor(leaver.id(), "deactivated")).isEqualTo(1);
        assertThatThrownBy(() -> sessions.verify(session.token())).hasMessageContaining("expired");

        jdbc.update("UPDATE sessions SET revoked_at = NULL WHERE user_id = ?", leaver.id());
        assertThatThrownBy(() -> sessions.verify(session.token()))
                .hasMessageContaining("no longer active");
    }

    /**
     * A demotion takes effect on the next request, without waiting for the token to expire.
     *
     * <p>This is the property the whole "no claims in the token" decision buys, so it is asserted
     * on a session that is deliberately <em>not</em> revoked: the permission check must come from
     * the database even when nothing has ended the session.
     */
    @Test
    void aDemotionBitesOnTheNextRequest() {
        ShopFixture.Shop shop = fixtures.seed();
        Actor demoted = newActor(shop, Role.MANAGER);
        Session session = open(shop, demoted);

        users.update(shop.tenantId(), demoted.id(), "Now a cashier", Role.CASHIER);

        assertThat(sessions.verify(session.token()).role()).isEqualTo(Role.CASHIER);
        assertThatThrownBy(() -> sessions.require(session.token(), Permission.BACK_OFFICE))
                .hasMessageContaining("cannot open the back office");
    }

    // ------------------------------------------------------------------------- refresh

    @Test
    void refreshExtendsTheSameSessionRatherThanOpeningAnother() {
        ShopFixture.Shop shop = fixtures.seed();
        Actor manager = newActor(shop, Role.MANAGER);
        Session first = open(shop, manager);
        UUID jti = onlyJtiFor(manager.id());

        Session second = sessions.refresh(first.token());

        assertThat(onlyJtiFor(manager.id())).isEqualTo(jti);
        assertThat(second.expiresAt()).isAfterOrEqualTo(first.expiresAt());
        assertThat(sessions.verify(second.token()).id()).isEqualTo(manager.id());
    }

    /** A session that has been signed out cannot refresh its way back to life. */
    @Test
    void aRevokedSessionCannotRefresh() {
        ShopFixture.Shop shop = fixtures.seed();
        Session session = open(shop, newActor(shop, Role.MANAGER));
        sessions.revoke(session.token(), "signed out");

        assertThatThrownBy(() -> sessions.refresh(session.token())).hasMessageContaining("expired");
    }

    // ---------------------------------------------------------------------- forged tokens

    /**
     * A token signed with somebody else's key is not a token.
     *
     * <p>Written out rather than trusted to the library, because "the signature is checked" is the
     * one assumption whose failure looks exactly like everything working.
     */
    @Test
    void aTokenSignedWithAnotherKeyIsRefused() {
        ShopFixture.Shop shop = fixtures.seed();
        Actor manager = newActor(shop, Role.MANAGER);
        Session real = open(shop, manager);
        UUID jti = onlyJtiFor(manager.id());

        byte[] wrong = new byte[32];
        java.util.Arrays.fill(wrong, (byte) 7);
        String forged =
                Jwts.builder()
                        .header()
                        .keyId(keys.active().kid())
                        .and()
                        .id(jti.toString())
                        .subject(Long.toString(manager.id()))
                        .issuer("lumora-till")
                        .expiration(java.util.Date.from(real.expiresAt()))
                        .signWith(new SecretKeySpec(wrong, "HmacSHA256"), Jwts.SIG.HS256)
                        .compact();

        assertThatThrownBy(() -> sessions.verify(forged)).hasMessageContaining("not valid");
    }

    /**
     * An unsigned token naming a real session is refused.
     *
     * <p>{@code alg: none} is the oldest JWT mistake there is, and the one a hand-rolled verifier
     * gets wrong. Rejecting it is a property of this build, so it is asserted here rather than
     * assumed of the library.
     */
    @Test
    void anUnsignedTokenIsRefused() {
        ShopFixture.Shop shop = fixtures.seed();
        Actor manager = newActor(shop, Role.MANAGER);
        open(shop, manager);
        UUID jti = onlyJtiFor(manager.id());

        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String unsigned =
                b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                        + "."
                        + b64.encodeToString(
                                ("{\"jti\":\"" + jti + "\",\"iss\":\"lumora-till\"}")
                                        .getBytes(StandardCharsets.UTF_8))
                        + ".";

        assertThatThrownBy(() -> sessions.verify(unsigned)).hasMessageContaining("not valid");
    }

    /** No token at all says what to do about it, rather than "not valid". */
    @Test
    void noTokenAsksForASignIn() {
        assertThatThrownBy(() -> sessions.verify(null)).hasMessageContaining("Sign in");
        assertThatThrownBy(() -> sessions.verify("   ")).hasMessageContaining("Sign in");
    }

    // ------------------------------------------------------------------- the signing key

    /**
     * The key is provisioned on this machine and there is exactly one active one.
     *
     * <p>Both matter. Generated here is what makes a token from another shop's till meaningless
     * here; one active key is what stops two boots leaving a database in which it is ambiguous
     * which key new tokens are signed with.
     */
    @Test
    void thereIsExactlyOneActiveSigningKeyAndItWasMadeHere() {
        SigningKeyStore.Key first = keys.active();
        SigningKeyStore.Key again = keys.active();

        assertThat(again.kid()).isEqualTo(first.kid());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM signing_keys WHERE active", Integer.class))
                .isEqualTo(1);
    }

    /**
     * Rotating the key does not sign the shop out.
     *
     * <p>Tokens already in somebody's hands were signed by the retired key and name it in their
     * {@code kid}, so they keep verifying until they expire — minutes. Without that, rotation
     * would empty every screen in the shop at once, which is how a shop learns not to rotate.
     */
    @Test
    void rotatingTheKeyLeavesTokensAlreadyIssuedWorking() {
        ShopFixture.Shop shop = fixtures.seed();
        Actor manager = newActor(shop, Role.MANAGER);
        Session before = open(shop, manager);
        String oldKid = keys.active().kid();

        SigningKeyStore.Key rotated = keys.rotate();

        assertThat(rotated.kid()).isNotEqualTo(oldKid);
        assertThat(sessions.verify(before.token()).id()).isEqualTo(manager.id());
        assertThat(sessions.verify(open(shop, manager).token()).id()).isEqualTo(manager.id());
    }

    // ------------------------------------------------------------------------- helpers

    /**
     * A user nobody else in this class shares.
     *
     * <p>{@code ShopFixture} deliberately reuses one tenant and one MGR across every seed, which
     * is right for the tests that only need somebody privileged. It is wrong here: half of these
     * assertions count the session rows a user has, and a shared user accumulates them across
     * tests — turning a genuine failure into a passing run in one order and a mystery in another.
     */
    private Actor newActor(ShopFixture.Shop shop, Role role) {
        String code = "SES" + UNIQUE.incrementAndGet();
        UserRow user =
                users.create(shop.tenantId(), UUID.randomUUID(), code, "Session probe", role, PIN);
        return new Actor(user.id(), code);
    }

    private record Actor(long id, String code) {}

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    private static final String PIN = "4821";

    private Session open(ShopFixture.Shop shop, Actor actor) {
        return sessions.open(shop.tenantId(), actor.code(), PIN, SURFACE, Permission.BACK_OFFICE);
    }

    private int sessionCountFor(long userId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM sessions WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    private UUID onlyJtiFor(long userId) {
        return jdbc.queryForObject(
                "SELECT jti FROM sessions WHERE user_id = ?", UUID.class, userId);
    }

    /** The header and payload of a JWT, decoded. The signature is bytes and never readable text. */
    private static String decodeSegments(String token) {
        String[] parts = token.split("\\.");
        Base64.Decoder decoder = Base64.getUrlDecoder();
        return new String(decoder.decode(parts[0]), StandardCharsets.UTF_8)
                + new String(decoder.decode(parts[1]), StandardCharsets.UTF_8);
    }
}

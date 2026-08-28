package com.lumora.pos.auth;

import com.lumora.pos.auth.SessionService.Session;
import com.lumora.pos.shop.LocalShop;
import com.lumora.pos.user.Permission;
import com.lumora.pos.user.Role;
import com.lumora.pos.user.UserService.Operator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one door into anything that needs to know who is asking (M3-09).
 *
 * <p>Four verbs and no more: open a session, look at it, extend it, end it. The PIN appears in
 * exactly one of them — the {@code POST} — and nowhere else in this codebase after M3-09, which is
 * the whole point of the milestone. {@code OperatorGate}'s {@code X-Operator-Pin} header is gone
 * rather than deprecated: two ways to authenticate is how one of them stops being maintained.
 *
 * <p>Everything here works with the network cable out. There is no token endpoint to call, no
 * discovery document to fetch and no clock to agree with; the key, the hashes and the session rows
 * are all on this machine.
 */
/*
 * Desktop profile only.
 *
 * <p>Without this the class is a bean under every profile, so the cloud instance mounted it too —
 * behind M4-01's filter, but mounted. Everything it calls goes through {@code LocalShop}, which
 * asserts the database holds exactly one tenant, so on the cloud it could only ever fail. A route
 * that exists and always fails is worse than one that does not exist: it is a promise in the URL
 * space that somebody eventually tries to keep.
 */
@RestController
@RequestMapping("/api/auth")
@Profile("desktop")
public class AuthController {

    /** What the back office calls itself when it opens a session. Recorded on the row. */
    public static final String BACK_OFFICE_SURFACE = "BACK_OFFICE";

    private final SessionService sessions;
    private final LocalShop shop;

    public AuthController(SessionService sessions, LocalShop shop) {
        this.sessions = sessions;
        this.shop = shop;
    }

    /**
     * Exchanges a code and PIN for a token.
     *
     * <p>Opened against {@link Permission#BACK_OFFICE}: a cashier signing in here is refused at
     * the door rather than handed a token that every screen will then reject one at a time.
     */
    @PostMapping("/session")
    public SessionResponse signIn(@Valid @RequestBody SignInRequest request) {
        Session session =
                sessions.open(
                        shop.soleTenantId(),
                        request.code(),
                        request.pin(),
                        BACK_OFFICE_SURFACE,
                        Permission.BACK_OFFICE);
        return SessionResponse.of(session);
    }

    /**
     * Who this token is, as the shell asks on a reload.
     *
     * <p>Returns the role and permissions read <em>now</em>, not as they were at sign-in. A
     * manager demoted an hour ago sees a back office with the right things missing, because the
     * answer comes from the database rather than from the token.
     */
    @GetMapping("/session")
    public WhoAmI whoAmI(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer) {
        Operator operator = sessions.require(bearer, Permission.BACK_OFFICE);
        return new WhoAmI(
                operator.id(),
                operator.code(),
                operator.displayName(),
                operator.role(),
                operator.role().permissions());
    }

    /** Extends the session and returns a fresh token. The client calls this before it expires. */
    @PostMapping("/session/refresh")
    public SessionResponse refresh(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer) {
        return SessionResponse.of(sessions.refresh(bearer));
    }

    /**
     * Signs out, and means it: the row is revoked, so the token stops working immediately rather
     * than at its expiry. Returns 200 with no body whatever the token was, because "you were
     * already signed out" is not a failure anybody can act on.
     */
    @DeleteMapping("/session")
    public void signOut(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer) {
        sessions.revoke(bearer, "signed out");
    }

    // ------------------------------------------------------------------------- payloads

    public record SignInRequest(@NotBlank String code, @NotBlank String pin) {}

    /**
     * @param expiresAt so the client can refresh before the token dies rather than discovering it
     *     did, halfway through saving a product.
     */
    public record SessionResponse(
            String token,
            Instant expiresAt,
            long id,
            String code,
            String displayName,
            Role role,
            Set<Permission> permissions) {

        static SessionResponse of(Session session) {
            Operator operator = session.operator();
            return new SessionResponse(
                    session.token(),
                    session.expiresAt(),
                    operator.id(),
                    operator.code(),
                    operator.displayName(),
                    operator.role(),
                    operator.role().permissions());
        }
    }

    public record WhoAmI(
            long id, String code, String displayName, Role role, Set<Permission> permissions) {}
}

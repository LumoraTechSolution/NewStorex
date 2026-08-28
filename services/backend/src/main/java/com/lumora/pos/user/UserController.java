package com.lumora.pos.user;

import com.lumora.pos.auth.SessionService;
import com.lumora.pos.shop.LocalShop;
import com.lumora.pos.user.UserService.UserRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/**
 * Users, roles and PINs (M3-08).
 *
 * <p>Every endpoint takes the acting operator from a bearer token (M3-09). Reading the user list
 * needs {@link Permission#BACK_OFFICE}; changing anything needs {@link Permission#MANAGE_USERS},
 * which only an OWNER holds. The split is deliberate — a manager should be able to see who has an
 * account without being able to appoint one.
 *
 * <h2>Changing a user ends that user's sessions</h2>
 *
 * Deactivating somebody, demoting them, or resetting their PIN are the three moments at which a
 * session opened under the old facts is exactly the thing that must stop working. {@code
 * SessionService.verify} re-reads the role on every request, so a demotion already bites — but the
 * session is revoked as well, so the person is told plainly that they have been signed out rather
 * than discovering it as screens quietly emptying.
 *
 * <p>The revocation lives here, in the controller, and not inside {@code UserService}: {@code
 * SessionService} depends on {@code UserService} to authenticate, so the reverse dependency would
 * be a cycle. Signing in must not need the thing that signs people out.
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
@RequestMapping("/api/users")
@Profile("desktop")
public class UserController {

    private final UserService users;
    private final SessionService sessions;
    private final LocalShop shop;

    public UserController(UserService users, SessionService sessions, LocalShop shop) {
        this.users = users;
        this.sessions = sessions;
        this.shop = shop;
    }

    @GetMapping
    public List<UserRow> list(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer) {
        sessions.require(bearer, Permission.BACK_OFFICE);
        return users.list(shop.soleTenantId());
    }

    @PostMapping
    public UserRow create(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @Valid @RequestBody CreateUserRequest request) {
        sessions.require(bearer, Permission.MANAGE_USERS);
        return users.create(
                shop.soleTenantId(),
                request.clientUuid(),
                request.code(),
                request.displayName(),
                request.role(),
                request.pin());
    }

    @PutMapping("/{userId}")
    public UserRow update(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @PathVariable long userId,
            @Valid @RequestBody UpdateUserRequest request) {
        sessions.require(bearer, Permission.MANAGE_USERS);
        UserRow updated =
                users.update(shop.soleTenantId(), userId, request.displayName(), request.role());
        sessions.revokeAllFor(userId, "role or name changed");
        return updated;
    }

    /**
     * Sets a user's PIN. Its own endpoint, never a field on {@link UpdateUserRequest} — see {@code
     * UserService.setPin} for why a rename must not be able to reset a credential.
     */
    @PutMapping("/{userId}/pin")
    public void setPin(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @PathVariable long userId,
            @Valid @RequestBody SetPinRequest request) {
        sessions.require(bearer, Permission.MANAGE_USERS);
        users.setPin(shop.soleTenantId(), userId, request.pin());
        sessions.revokeAllFor(userId, "PIN reset");
    }

    /** Deactivates or reinstates. There is no DELETE — history has to keep resolving. */
    @PutMapping("/{userId}/active")
    public UserRow setActive(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @PathVariable long userId,
            @Valid @RequestBody SetActiveRequest request) {
        sessions.require(bearer, Permission.MANAGE_USERS);
        UserRow updated = users.setActive(shop.soleTenantId(), userId, request.active());
        if (!updated.active()) {
            sessions.revokeAllFor(userId, "deactivated");
        }
        return updated;
    }

    // ------------------------------------------------------------------------- payloads

    public record CreateUserRequest(
            @NotNull UUID clientUuid,
            @NotBlank String code,
            @NotBlank String displayName,
            @NotNull Role role,
            @NotBlank String pin) {}

    public record UpdateUserRequest(@NotBlank String displayName, @NotNull Role role) {}

    public record SetPinRequest(@NotBlank String pin) {}

    public record SetActiveRequest(boolean active) {}
}

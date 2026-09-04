package com.lumora.pos.customer;

import com.lumora.pos.auth.SessionService;
import com.lumora.pos.customer.CustomerService.CustomerRow;
import com.lumora.pos.customer.CustomerService.CustomerSale;
import com.lumora.pos.shift.ShiftService;
import com.lumora.pos.shop.LocalShop;
import com.lumora.pos.user.Permission;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customers, from the till and from the back office (M3-11).
 *
 * <h2>Two doors, and they are gated differently on purpose</h2>
 *
 * {@code /api/customers/**} is the till's: look somebody up by number, and add one who is standing
 * at the counter. It is ungated, exactly like the product lookup and the sale commit beside it —
 * the sales screen has no session in v1 and inventing one only for this endpoint would mean the
 * cashier types a PIN to write down a phone number, which ends with nobody recording customers at
 * all. What stands behind it is the same thing that stands behind committing a sale: a loopback
 * bind, and a shift that has to be open.
 *
 * <p>{@code /api/back-office/customers/**} is the owner's: edit, deactivate, read somebody's whole
 * purchase history. That needs {@link Permission#BACK_OFFICE}, because a purchase history is the
 * one thing here that is genuinely private and the one thing a till never needs.
 *
 * <h2>Creating from the till records who did it</h2>
 *
 * {@code customers.created_by} is a real foreign key, and the till has no session to name. The
 * person who opened the shift is who it names — which is exactly who is standing at the keypad, and
 * the same attribution every sale rung up on that shift already carries.
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
@Profile("desktop")
public class CustomerController {

    private static final int SEARCH_LIMIT = 25;
    private static final int HISTORY_LIMIT = 50;

    private final CustomerService customers;
    private final CustomerPrivacyService privacy;
    private final SessionService sessions;
    private final ShiftService shifts;
    private final LocalShop shop;

    public CustomerController(
            CustomerService customers,
            CustomerPrivacyService privacy,
            SessionService sessions,
            ShiftService shifts,
            LocalShop shop) {
        this.customers = customers;
        this.privacy = privacy;
        this.sessions = sessions;
        this.shifts = shifts;
        this.shop = shop;
    }

    // ---------------------------------------------------------------------------- the till

    /**
     * Find somebody by a leading run of digits or a piece of their name.
     *
     * <p>Active only. A deactivated customer is one the shop has decided not to serve under that
     * record any more, and offering them at the till would make deactivation a suggestion.
     */
    @GetMapping("/api/customers")
    public List<CustomerRow> search(@RequestParam(required = false) String q) {
        return customers.search(shop.soleTenantId(), q, false, SEARCH_LIMIT);
    }

    @PostMapping("/api/customers")
    public CustomerRow createFromTill(@Valid @RequestBody CreateCustomerRequest request) {
        LocalShop.Branch branch = shop.branch(request.branchCode());
        ShiftService.OpenShift shift =
                shifts.requireOpenShift(branch.tenantId(), branch.id(), request.terminalCode());
        return customers.create(
                branch.tenantId(),
                request.clientUuid(),
                request.name(),
                request.phone(),
                request.email(),
                request.note(),
                shift.operatorId());
    }

    // ---------------------------------------------------------------------- the back office

    @GetMapping("/api/back-office/customers")
    public List<CustomerRow> list(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        sessions.require(bearer, Permission.BACK_OFFICE);
        return customers.search(shop.soleTenantId(), q, includeInactive, 200);
    }

    @PostMapping("/api/back-office/customers")
    public CustomerRow create(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer,
            @Valid @RequestBody SaveCustomerRequest request) {
        var operator = sessions.require(bearer, Permission.BACK_OFFICE);
        return customers.create(
                shop.soleTenantId(),
                request.clientUuid(),
                request.name(),
                request.phone(),
                request.email(),
                request.note(),
                operator.id());
    }

    @PutMapping("/api/back-office/customers/{customerId}")
    public CustomerRow update(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer,
            @PathVariable long customerId,
            @Valid @RequestBody UpdateCustomerRequest request) {
        sessions.require(bearer, Permission.BACK_OFFICE);
        return customers.update(
                shop.soleTenantId(),
                customerId,
                request.name(),
                request.phone(),
                request.email(),
                request.note());
    }

    @PutMapping("/api/back-office/customers/{customerId}/active")
    public CustomerRow setActive(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer,
            @PathVariable long customerId,
            @Valid @RequestBody SetActiveRequest request) {
        sessions.require(bearer, Permission.BACK_OFFICE);
        return customers.setActive(shop.soleTenantId(), customerId, request.active());
    }

    /** What this person has bought. The one endpoint the till has no business calling. */
    @GetMapping("/api/back-office/customers/{customerId}/history")
    public List<CustomerSale> history(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer,
            @PathVariable long customerId) {
        sessions.require(bearer, Permission.BACK_OFFICE);
        return customers.history(shop.soleTenantId(), customerId, HISTORY_LIMIT);
    }

    // ------------------------------------------------------------------------------ PDPA

    /**
     * Everything the shop holds about one person (M5-10).
     *
     * <p>Behind a back-office session like the history endpoint beside it, and for a stronger
     * reason: this is the most concentrated piece of personal data the system can produce, and an
     * export of the wrong customer handed to the wrong person is itself the breach the law is
     * about. The till cannot call it.
     */
    @GetMapping("/api/back-office/customers/{customerId}/data-export")
    public CustomerPrivacyService.DataExport dataExport(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer,
            @PathVariable long customerId) {
        sessions.require(bearer, Permission.BACK_OFFICE);
        return privacy.export(shop.soleTenantId(), customerId);
    }

    /**
     * Destroys the personal data on one customer, permanently (M5-10).
     *
     * <p>{@code POST} and not {@code DELETE}. The row is not deleted and saying so in the method is
     * the honest description — what happens is that fields are overwritten and an audit column is
     * written, which is a creation as much as a removal. A {@code DELETE} here would also read, to
     * anybody adding to this file later, as an invitation to make it actually delete.
     *
     * <p>The operator is named from their own session rather than from the request body. Who
     * actioned an erasure is the only thing anybody will ask afterwards, and a client-supplied
     * answer to that question is worth nothing.
     */
    @PostMapping("/api/back-office/customers/{customerId}/erase")
    public CustomerPrivacyService.Erased erase(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer,
            @PathVariable long customerId) {
        var operator = sessions.require(bearer, Permission.BACK_OFFICE);
        return privacy.erase(shop.soleTenantId(), customerId, operator.id());
    }

    // -------------------------------------------------------------------------- payloads

    /**
     * @param branchCode and {@code terminalCode} so the open shift can be found — that is where the
     *     {@code created_by} attribution comes from, and it is why this is not the same record the
     *     back office posts.
     */
    public record CreateCustomerRequest(
            @NotNull UUID clientUuid,
            @NotBlank String branchCode,
            @NotBlank String terminalCode,
            @NotBlank String name,
            String phone,
            String email,
            String note) {}

    public record SaveCustomerRequest(
            @NotNull UUID clientUuid,
            @NotBlank String name,
            String phone,
            String email,
            String note) {}

    /** No {@code clientUuid}: an update names the row in the path, and a body that also carried an
     * identity would be a second answer to the same question — one of which would eventually be
     * wrong. */
    public record UpdateCustomerRequest(
            @NotBlank String name, String phone, String email, String note) {}

    public record SetActiveRequest(boolean active) {}
}

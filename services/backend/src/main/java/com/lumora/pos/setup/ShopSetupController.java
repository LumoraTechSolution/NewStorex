package com.lumora.pos.setup;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The first-run wizard's API (M5-03).
 *
 * <h2>Unauthenticated, and why that is safe here and nowhere else</h2>
 *
 * Every other write endpoint on this service takes a bearer token and a {@code Permission}. This
 * one cannot: it runs on a till that has no users yet, so there is nobody to authenticate as —
 * the same circularity {@code PlatformBootstrap} faces in the cloud, and it is resolved the same
 * way. <b>The empty database is the credential.</b> {@link ShopSetupService#provision} refuses
 * once a tenant exists, inside the transaction that would create the second one, so this endpoint
 * is answerable exactly once in the life of a till and is inert every moment afterwards.
 *
 * <p>What that leaves is a window between installation and setup in which an unauthenticated
 * caller could provision the shop. Three things bound it, and they are the reason this is
 * acceptable rather than merely convenient:
 *
 * <ul>
 *   <li><b>The desktop profile binds {@code 127.0.0.1}.</b> Reaching this endpoint means already
 *       having code running on the shop's PC, which is a larger problem than a wrongly-named
 *       shop. This is the same property that lets the till's other endpoints trust their caller.
 *   <li><b>The window is minutes long</b> and ends the first time somebody completes the wizard —
 *       which is the first thing anyone does with a freshly installed till.
 *   <li><b>The damage is visible and recoverable.</b> A shop provisioned with the wrong name is
 *       wrong on the next receipt printed, in the largest text on it. Nothing is disclosed: this
 *       endpoint reads nothing and there is nothing yet to read.
 * </ul>
 *
 * <p>The alternative — an installer-generated secret the shopkeeper types before the wizard —
 * buys very little against a loopback-only attacker who has already lost, and costs the one thing
 * this task exists to remove: a step where somebody transcribes a code from one place to another.
 *
 * <p><b>Desktop only.</b> {@code @Profile("desktop")} keeps it off the cloud entirely. The cloud
 * has many tenants and creates them through an authenticated platform session; an endpoint that
 * creates a tenant when a table is empty would be a very different thing there, and the profile
 * annotation means it never has to be reasoned about.
 */
@RestController
@RequestMapping("/api/setup")
@Profile("desktop")
public class ShopSetupController {

    private final ShopSetupService setup;
    private final CloudCredentialCheck cloudCheck;

    public ShopSetupController(ShopSetupService setup, CloudCredentialCheck cloudCheck) {
        this.setup = setup;
        this.cloudCheck = cloudCheck;
    }

    /**
     * Whether this till still needs setting up.
     *
     * <p>The terminal asks this before it renders anything, so a provisioned till goes straight to
     * the sign-in screen and an unprovisioned one goes straight to the wizard. Deliberately
     * discloses nothing but the boolean: on a till that is already set up, the answer is the one
     * fact the caller could determine anyway by trying.
     */
    @GetMapping("/status")
    public SetupStatus status() {
        return new SetupStatus(setup.isProvisioned());
    }

    /**
     * The shop's name, address, branch and till code — what the terminal prints on a receipt.
     *
     * <p>Unauthenticated like the rest of this controller, and unlike every other read on this
     * service. What it returns is printed on every receipt the till hands across the counter, so
     * it is not a secret from anyone who can already reach a loopback port on the shop's own PC.
     * Notably it carries no VAT identity: {@code TaxInvoiceService} reads that separately, behind
     * a permission, because a TIN on a legal document is a different question from a shop name on
     * a receipt.
     */
    @GetMapping("/identity")
    public ShopSetupService.ShopIdentity identity() {
        return setup.identity();
    }

    /**
     * Checks a cloud token against the cloud before the wizard saves it.
     *
     * <p>Its own endpoint rather than part of {@code POST /shop}, because the two answer different
     * questions and fail differently. Setting up a shop must succeed with no network at all; this
     * needs one, and its most useful answer — "the cloud calls that token *jeewa stores*" — is
     * something the person setting up should see and confirm before anything is written.
     *
     * <p>Unauthenticated like the rest of this controller, and it needs no further justification
     * than the others: it takes a token the caller already holds and tells them whether it works.
     * It discloses nothing to somebody who does not have one.
     */
    @PostMapping("/cloud-check")
    public CloudCredentialCheck.Result checkCloud(@RequestBody CloudCheckRequest request) {
        return cloudCheck.check(request.cloudUrl(), request.token());
    }

    /** @param cloudUrl the address to ask, and {@code token} the credential to ask about */
    public record CloudCheckRequest(String cloudUrl, String token) {}

    /**
     * Creates the shop. Answerable once; every later call is a 422.
     *
     * <p>Returns what the wizard shows on its final screen and nothing secret — notably not the
     * PIN, which the person setting up chose and already knows.
     */
    @PostMapping("/shop")
    public ShopSetupService.Provisioned provision(@Valid @RequestBody ShopSetupRequest request) {
        return setup.provision(request);
    }

    /** @param provisioned true when this till already belongs to a shop */
    public record SetupStatus(boolean provisioned) {}
}

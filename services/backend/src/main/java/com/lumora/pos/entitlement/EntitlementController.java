package com.lumora.pos.entitlement;

import com.lumora.pos.shop.LocalShop;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the till's screens ask before showing a capability (M4-09).
 *
 * <p>A local read, off the shop's own database, answered whether or not there is a network. That is
 * the whole point of {@link EntitlementStore} caching the cloud's answer: a screen that had to ask
 * the cloud whether it may draw itself would be a screen that disappears during an outage.
 *
 * <p>Deliberately not gated behind the back-office session. It says which capabilities a shop has
 * bought and when its licence runs out — commercial facts about the machine, not shop data — and
 * the till's own status strip needs to show the renewal notice before anybody has signed in to
 * anything.
 */
@RestController
@RequestMapping("/api/entitlement")
@Profile("desktop")
public class EntitlementController {

    private final EntitlementStore store;
    private final LocalShop shop;

    public EntitlementController(EntitlementStore store, LocalShop shop) {
        this.store = store;
        this.shop = shop;
    }

    @GetMapping
    public View entitlement() {
        Optional<EntitlementStore.Cached> cached = store.cached(shop.soleTenantId());

        // The unasked till, and the case the screens spend most of their life in during
        // development. `known: false` rather than a licensed-looking placeholder: the UI turns it
        // into "allow everything and say nothing", and a placeholder that claimed a plan would put
        // a plan name on a screen that nobody has sold.
        if (cached.isEmpty()) {
            return new View(false, true, null, null, null, null, null, null, List.of());
        }

        EntitlementStore.Cached c = cached.get();
        return new View(
                true,
                c.licensed(),
                c.planCode(),
                c.planName(),
                c.licenceExpiresAt(),
                c.checkedAt(),
                c.licensedAt(),
                c.maxTerminals(),
                List.copyOf(c.flags()));
    }

    /**
     * @param known whether the cloud has ever answered this till. False is not a problem state —
     *     see {@link EntitlementStore}'s rule 1 — and the screens read it as full capability.
     * @param licensed the cached answer, not a live check. False here means the cloud said so when
     *     it was last reachable, and the till shows a renewal notice rather than locking anything.
     * @param flags empty when {@code known} is false, where it means "no restrictions recorded"
     *     rather than "nothing allowed". The distinction is carried by {@code known}, which is why
     *     that field exists at all.
     */
    public record View(
            boolean known,
            boolean licensed,
            String planCode,
            String planName,
            Instant licenceExpiresAt,
            Instant checkedAt,
            Instant licensedAt,
            Integer maxTerminals,
            List<String> flags) {}
}

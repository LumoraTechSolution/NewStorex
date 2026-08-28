package com.lumora.pos.sync;

import java.time.Instant;
import java.util.List;

/**
 * What the cloud says this shop is entitled to (M4-09) — the only thing in v1 that travels
 * <em>downward</em>.
 *
 * <p>Everything else in this package goes the other way, and that asymmetry is the architecture
 * rather than an accident: a sale is final on the shop PC and the cloud is told afterwards. This
 * record is the one exception, and it is safe to be one precisely because nothing about a sale
 * depends on it. A till that has never received one sells exactly as well as a till that received
 * one this second — see {@code EntitlementStore} for the rule that makes that true.
 *
 * <p>Shared by both profiles, like {@link SyncBatch} and {@link SyncBatchResult}: the cloud
 * assembles it and the till stores it, and one record means the two cannot describe the same shop
 * differently.
 *
 * @param licensed whether a licence period covers <em>now</em>. Derived from
 *     {@code tenant_licences} on every request, never stored — §A's "movements, not balances", one
 *     layer up.
 * @param planCode the plan of the covering licence, or of the most recent one when there is none.
 *     A lapsed shop still needs to be told what it had and when it ran out; that is the difference
 *     between a renewal notice and a mystery.
 * @param licenceExpiresAt when the licence ends, or ended. Non-null whenever {@code planCode} is.
 * @param flags the tenant's effective capabilities — its plan's features with its own overrides
 *     applied. <b>Empty when unlicensed</b>, which is not the same as "turn everything off": see
 *     {@code EntitlementStore#record} for what the till does with that.
 * @param asOf when the cloud computed this. The till stores it so a stale answer is legible as one
 *     rather than looking current.
 */
public record Entitlement(
        boolean licensed,
        String planCode,
        String planName,
        Instant licenceStartsAt,
        Instant licenceExpiresAt,
        Integer maxTerminals,
        Integer maxUsers,
        List<String> flags,
        Instant asOf) {

    /** A shop the cloud has no licence row for at all. Rare, and not the same as lapsed. */
    public static Entitlement unlicensed() {
        return new Entitlement(false, null, null, null, null, null, null, List.of(), Instant.now());
    }
}

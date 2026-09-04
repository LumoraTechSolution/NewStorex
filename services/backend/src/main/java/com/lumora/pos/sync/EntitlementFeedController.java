package com.lumora.pos.sync;

import com.lumora.pos.cloud.AuthenticatedPrincipal;
import com.lumora.pos.cloud.CloudPrincipals;
import com.lumora.pos.cloud.LicenceService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one thing the cloud tells a till (M4-09).
 *
 * <h2>Why this is a GET of its own and not a field on the batch response</h2>
 *
 * Bolting the entitlement onto {@link SyncBatchResult} is free — the till is already making that
 * call — and it is wrong for two reasons that both bite the shops that matter most.
 *
 * <p>The first is that a batch response only exists when there is a batch. A shop that closed at
 * six and whose outbox is empty makes no such call until somebody rings something up, so a licence
 * that lapses overnight is news that arrives after the first sale of the morning rather than
 * before it.
 *
 * <p>The second is fatal. A lapsed licence stops ingest — that is the commercial lever V209 chose
 * — so the push of a lapsed shop is a 401 with no body. Carrying this news on the batch response
 * would mean the till could hear "your licence expired" only while its licence had not expired.
 * The shop that needs the renewal notice is precisely the shop that would never receive one, and
 * the cashier would see nothing but an offline strip with no explanation.
 *
 * <p>So it is a separate GET, on the allowlist in {@code TenantAuthFilter} that authenticates a
 * till whose licence has run out, and the till pulls it on the same scheduled tick that drains the
 * outbox — the tick, not the request, is what M4-09 means by "the same sync tick".
 *
 * <h2>It is still a till endpoint</h2>
 *
 * {@code TILL} and nothing else. An owner's console session reads its plan through the console API
 * where the rest of its reads live, and a platform session has no tenant to ask about.
 */
@RestController
@RequestMapping("/api/sync")
@Profile("cloud")
public class EntitlementFeedController {

    private final LicenceService licences;
    private final JdbcTemplate jdbc;

    public EntitlementFeedController(LicenceService licences, JdbcTemplate jdbc) {
        this.licences = licences;
        this.jdbc = jdbc;
    }

    /**
     * The shop's name, as the cloud has it, for the token that just authenticated.
     *
     * <p>Read straight from {@code tenants} rather than carried on the principal: the principal
     * holds an id because that is all every other endpoint needs, and widening it to carry a name
     * would put a string on the hot path of every ingest to serve one endpoint that runs every
     * five minutes.
     */
    private String tenantName(long tenantId) {
        List<String> found =
                jdbc.queryForList("SELECT name FROM tenants WHERE id = ?", String.class, tenantId);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * What this shop is entitled to, computed fresh on every call.
     *
     * <p>Nothing here is cached or stored: {@code licensed} is a predicate over the append-only
     * licence rows and the flags are resolved from the plan and its overrides in one statement. A
     * licence that expires at noon is therefore live at 11:59 and lapsed at 12:01 with nothing
     * having run in between, which is what makes an expiry date mean anything without a job that
     * somebody has to remember to schedule.
     */
    @GetMapping("/entitlement")
    public Entitlement entitlement(HttpServletRequest request) {
        long tenantId =
                CloudPrincipals.require(request, AuthenticatedPrincipal.Kind.TILL).tenantId();

        // Named on every path, including the unlicensed one below. A shop whose licence the cloud
        // cannot find is exactly the shop whose operator wants to know which shop the cloud thinks
        // it is talking to.
        String tenantName = tenantName(tenantId);

        Optional<LicenceService.LicencedPlan> plan = licences.currentOrLast(tenantId);
        if (plan.isEmpty()) {
            // No licence row at all. Rare — provisioning always grants a trial — and deliberately
            // not the same shape as a lapse: there is no plan to name and no date to renew from.
            return Entitlement.unlicensed().withTenantName(tenantName);
        }

        LicenceService.LicencedPlan p = plan.get();
        // Asked unconditionally rather than only when live. effectiveFlags is itself gated on a
        // covering licence and returns nothing for a lapsed shop, so asking it is the one source
        // of that answer — recomputing the same condition here is how the two come to disagree.
        List<String> flags = List.copyOf(licences.effectiveFlags(tenantId));

        return new Entitlement(
                p.live(),
                p.code(),
                p.name(),
                p.startsAt(),
                p.expiresAt(),
                p.maxTerminals(),
                p.maxUsers(),
                flags,
                Instant.now(),
                tenantName);
    }
}

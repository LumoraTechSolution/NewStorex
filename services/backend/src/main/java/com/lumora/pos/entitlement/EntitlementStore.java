package com.lumora.pos.entitlement;

import com.lumora.pos.sync.Entitlement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The till's copy of what the cloud last said it may do (M4-09).
 *
 * <p>Three rules live here, and each is the answer to a way this feature could quietly become the
 * thing §A forbids — a network dependency on the critical path of the shop working.
 *
 * <h2>1. Never asked, never blocked</h2>
 *
 * {@link #allows} answers <b>true</b> for a till that has no cached answer. A fresh install, a shop
 * part-way through activation, and every till built before this milestone all have no row, and
 * every one of them must open in the morning with a working back office. The opposite default —
 * deny until told otherwise — is a till that cannot be used until a network call it has not been
 * configured for succeeds, which is not a licensing policy but a broken product.
 *
 * <h2>2. A lapse does not withdraw anything</h2>
 *
 * {@link #record} writes the licence state on every answer and writes the flags <b>only when the
 * answer says licensed</b>. A shop whose card expired keeps the capabilities it had, and the till
 * shows a renewal notice instead of removing the screens the owner runs the business from.
 *
 * <p>This is the argument V209 already made when it decided a lapsed licence stops ingest and does
 * not lock the owner out of the console. Cutting a shop's sync is a commercial lever. Locking a
 * shopkeeper out of their own catalogue over a late payment is taking their data hostage, and it
 * removes the one screen that would tell them how to fix it.
 *
 * <h2>3. The cache does not expire</h2>
 *
 * There is no staleness check anywhere below. A shop offline for a fortnight keeps everything.
 * {@code checked_at} exists so the screen can say how old the answer is; it exists to be
 * <em>shown</em>, never to be compared against a threshold that withdraws a feature — see the V119
 * header.
 */
@Service
@Profile("desktop")
public class EntitlementStore {

    private final JdbcTemplate jdbc;

    public EntitlementStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Whether this shop may use a capability.
     *
     * <p>Absent cache means yes — see rule 1. An empty <em>cached</em> flag set means no, and the
     * two are genuinely different states: one is a till that has never been told, the other is a
     * till that was told nothing is included.
     */
    @Transactional(readOnly = true)
    public boolean allows(long tenantId, String flagCode) {
        Optional<Cached> cached = cached(tenantId);
        return cached.isEmpty() || cached.get().flags().contains(flagCode);
    }

    /** The whole cached answer, for the screen that shows it and the endpoint the UI reads. */
    @Transactional(readOnly = true)
    public Optional<Cached> cached(long tenantId) {
        List<Cached> found =
                jdbc.query(
                        """
                        SELECT licensed, plan_code, plan_name, licence_starts_at, licence_expires_at,
                               max_terminals, max_users, tenant_name, checked_at, licensed_at
                          FROM entitlements WHERE tenant_id = ?
                        """,
                        (rs, row) ->
                                new Cached(
                                        rs.getBoolean("licensed"),
                                        rs.getString("plan_code"),
                                        rs.getString("plan_name"),
                                        instant(rs.getTimestamp("licence_starts_at")),
                                        instant(rs.getTimestamp("licence_expires_at")),
                                        (Integer) rs.getObject("max_terminals"),
                                        (Integer) rs.getObject("max_users"),
                                        rs.getString("tenant_name"),
                                        instant(rs.getTimestamp("checked_at")),
                                        instant(rs.getTimestamp("licensed_at")),
                                        Set.of()),
                        tenantId);

        if (found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(found.get(0).withFlags(readFlags(tenantId)));
    }

    /**
     * Stores an answer from the cloud.
     *
     * <p>One transaction, because a row saying "licensed" beside a flag set from a different answer
     * is a shop whose screen and whose capabilities describe two different moments.
     *
     * <p>The flag write is guarded on {@code licensed} and the guard is the point — see rule 2. An
     * unlicensed answer carries no flags by construction, since the cloud resolves them from a
     * covering licence, so writing it through would clear the set and shut the back office of a
     * shop that is merely late paying.
     */
    @Transactional
    public void record(long tenantId, Entitlement entitlement) {
        jdbc.update(
                """
                INSERT INTO entitlements
                    (tenant_id, licensed, plan_code, plan_name, licence_starts_at,
                     licence_expires_at, max_terminals, max_users, tenant_name,
                     checked_at, licensed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), CASE WHEN ? THEN now() ELSE NULL END)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    licensed = excluded.licensed,
                    plan_code = excluded.plan_code,
                    plan_name = excluded.plan_name,
                    licence_starts_at = excluded.licence_starts_at,
                    licence_expires_at = excluded.licence_expires_at,
                    max_terminals = excluded.max_terminals,
                    max_users = excluded.max_users,
                    -- coalesce, so an older cloud that does not send a name does not erase one a
                    -- newer one already supplied. A name is only ever replaced by another name.
                    tenant_name = coalesce(excluded.tenant_name, entitlements.tenant_name),
                    checked_at = now(),
                    licensed_at = coalesce(excluded.licensed_at, entitlements.licensed_at)
                """,
                tenantId,
                entitlement.licensed(),
                entitlement.planCode(),
                entitlement.planName(),
                timestamp(entitlement.licenceStartsAt()),
                timestamp(entitlement.licenceExpiresAt()),
                entitlement.maxTerminals(),
                entitlement.maxUsers(),
                entitlement.tenantName(),
                entitlement.licensed());

        if (!entitlement.licensed()) {
            return;
        }

        // Replaced wholesale rather than merged. A capability withdrawn by the plan has to
        // disappear, and a diff would need the cloud to say what was removed — a second way of
        // describing the same set, and the way two descriptions come to disagree.
        jdbc.update("DELETE FROM entitlement_flags WHERE tenant_id = ?", tenantId);
        for (String flag : entitlement.flags()) {
            jdbc.update(
                    "INSERT INTO entitlement_flags (tenant_id, flag_code) VALUES (?, ?)"
                            + " ON CONFLICT DO NOTHING",
                    tenantId,
                    flag);
        }
    }

    private Set<String> readFlags(long tenantId) {
        return new TreeSet<>(
                jdbc.queryForList(
                        "SELECT flag_code FROM entitlement_flags WHERE tenant_id = ? ORDER BY flag_code",
                        String.class,
                        tenantId));
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    /**
     * @param checkedAt when the cloud last answered — shown, never compared against a threshold
     * @param licensedAt when it last answered yes. Kept rather than cleared on a lapse, so "you
     *     were fine until the 14th" stays answerable after the licence runs out.
     */
    public record Cached(
            boolean licensed,
            String planCode,
            String planName,
            Instant licenceStartsAt,
            Instant licenceExpiresAt,
            Integer maxTerminals,
            Integer maxUsers,
            /**
             * The shop name the cloud returned for this till's token, or null before the first
             * sync. Shown beside the till's own name so a credential pointing at the wrong shop is
             * visible — never enforced. See V122 for the incident that added it.
             */
            String tenantName,
            Instant checkedAt,
            Instant licensedAt,
            Set<String> flags) {

        Cached withFlags(Set<String> replacement) {
            return new Cached(
                    licensed,
                    planCode,
                    planName,
                    licenceStartsAt,
                    licenceExpiresAt,
                    maxTerminals,
                    maxUsers,
                    tenantName,
                    checkedAt,
                    licensedAt,
                    replacement);
        }
    }
}

package com.lumora.pos.cloud;

import com.lumora.pos.web.RejectedException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a shop is paid up for, and what that entitles it to (M4-08).
 *
 * <h2>Licence state is derived, never stored</h2>
 *
 * §A's rule is never to sync a level, only the movements that produce it. A licence period is that
 * rule one layer up: {@code tenant_licences} is append-only, and "is this shop licensed?" is a
 * predicate over it rather than a boolean anybody maintains. Renewal inserts. Nothing updates, so
 * there is no write that can lose a shop's history, and a billing dispute is settled by reading the
 * table rather than by believing a column.
 *
 * <h2>Effective flags are the plan, then the overrides</h2>
 *
 * A tenant's capabilities are its plan's features with its own overrides applied on top — and an
 * override can subtract as well as add, which is why it carries a boolean rather than being present
 * or absent. Resolved in one query so that the answer cannot differ between the screen that shows
 * it and the sync tick that sends it. <b>Nothing consumes this on the till yet</b>: M4-09 is what
 * pulls it down, and until then these are flags the cloud knows and the shop does not.
 */
@Service
@Profile("cloud")
public class LicenceService {

    /** What a new shop is provisioned on when nobody says otherwise. */
    public static final String DEFAULT_PLAN_CODE = "trial";

    /** How long that trial runs. Thirty days, matching what the seeded plan's text promises. */
    public static final int DEFAULT_TRIAL_DAYS = 30;

    private final JdbcTemplate jdbc;

    public LicenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ plans

    @Transactional(readOnly = true)
    public List<Plan> plans(boolean includeRetired) {
        return jdbc.query(
                """
                SELECT p.id, p.code, p.name, p.description, p.price_minor,
                       p.max_terminals, p.max_users, p.active, p.sort_order,
                       coalesce(
                         (SELECT string_agg(pf.flag_code, ',' ORDER BY pf.flag_code)
                            FROM plan_features pf WHERE pf.plan_id = p.id), '') AS flags
                  FROM plans p
                 WHERE (? OR p.active)
                 ORDER BY p.sort_order, p.code
                """,
                (rs, row) ->
                        new Plan(
                                rs.getLong("id"),
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getString("description"),
                                rs.getLong("price_minor"),
                                (Integer) rs.getObject("max_terminals"),
                                (Integer) rs.getObject("max_users"),
                                rs.getBoolean("active"),
                                splitFlags(rs.getString("flags"))),
                includeRetired);
    }

    @Transactional(readOnly = true)
    public List<FeatureFlag> knownFlags() {
        return jdbc.query(
                "SELECT code, name, description FROM feature_flags ORDER BY code",
                (rs, row) ->
                        new FeatureFlag(
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getString("description")));
    }

    /**
     * @throws RejectedException naming the code, because the caller typed it. A plan code that does
     *     not exist is a typo on a screen, not a server fault.
     */
    @Transactional(readOnly = true)
    public long planIdForCode(String code) {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM plans WHERE code = ?", Long.class, normalise(code));
        } catch (EmptyResultDataAccessException e) {
            throw new RejectedException("There is no plan with the code '" + normalise(code) + "'.");
        }
    }

    // ------------------------------------------------------------------ licences

    /**
     * Grants a period. Appends — a renewal is a new row, and the previous one is left exactly as it
     * was so the shop's licensing history stays readable.
     *
     * <p>{@link Propagation#MANDATORY} because every caller is already in a transaction that also
     * writes the audit row, and a grant that commits without its audit entry is a grant nobody can
     * be asked about.
     *
     * @param startsAt null means now. A future date is legitimate — a renewal arranged in advance.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Licence grant(
            long tenantId,
            String planCode,
            Instant startsAt,
            Instant expiresAt,
            String note,
            Long grantedBy) {

        long planId = planIdForCode(planCode);
        Instant from = startsAt == null ? Instant.now() : startsAt;
        if (expiresAt == null || !expiresAt.isAfter(from)) {
            throw new RejectedException("A licence has to end after it starts.");
        }

        long id =
                jdbc.queryForObject(
                        """
                        INSERT INTO tenant_licences
                            (tenant_id, plan_id, starts_at, expires_at, note, granted_by)
                        VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                        """,
                        Long.class,
                        tenantId,
                        planId,
                        java.sql.Timestamp.from(from),
                        java.sql.Timestamp.from(expiresAt),
                        note == null ? "" : note,
                        grantedBy);

        return new Licence(id, normalise(planCode), from, expiresAt, note == null ? "" : note);
    }

    /** Every period this shop has ever been granted, newest first. */
    @Transactional(readOnly = true)
    public List<Licence> history(long tenantId) {
        return jdbc.query(
                """
                SELECT l.id, p.code, l.starts_at, l.expires_at, l.note
                  FROM tenant_licences l JOIN plans p ON p.id = l.plan_id
                 WHERE l.tenant_id = ?
                 ORDER BY l.starts_at DESC, l.id DESC
                """,
                (rs, row) ->
                        new Licence(
                                rs.getLong("id"),
                                rs.getString("code"),
                                rs.getTimestamp("starts_at").toInstant(),
                                rs.getTimestamp("expires_at").toInstant(),
                                rs.getString("note")),
                tenantId);
    }

    /**
     * The period covering now, if there is one.
     *
     * <p>{@code ORDER BY starts_at DESC} settles overlaps in favour of the most recently begun,
     * which is what an upgrade mid-period means: the new plan applies from the moment it starts,
     * and the old row stays as the record of what was replaced.
     */
    @Transactional(readOnly = true)
    public Optional<Licence> current(long tenantId) {
        return jdbc
                .query(
                        """
                        SELECT l.id, p.code, l.starts_at, l.expires_at, l.note
                          FROM tenant_licences l JOIN plans p ON p.id = l.plan_id
                         WHERE l.tenant_id = ?
                           AND l.starts_at <= now() AND l.expires_at > now()
                         ORDER BY l.starts_at DESC, l.id DESC
                         LIMIT 1
                        """,
                        (rs, row) ->
                                new Licence(
                                        rs.getLong("id"),
                                        rs.getString("code"),
                                        rs.getTimestamp("starts_at").toInstant(),
                                        rs.getTimestamp("expires_at").toInstant(),
                                        rs.getString("note")),
                        tenantId)
                .stream()
                .findFirst();
    }

    // ------------------------------------------------------------------ flags

    /**
     * Sets or clears one tenant's departure from its plan.
     *
     * @param enabled null removes the override, putting the tenant back on whatever its plan says.
     *     Distinct from {@code false}, which is a deliberate withdrawal and stays visible as one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void setOverride(long tenantId, String flagCode, Boolean enabled, String note, Long by) {
        String code = normalise(flagCode);
        if (enabled == null) {
            jdbc.update(
                    "DELETE FROM tenant_feature_overrides WHERE tenant_id = ? AND flag_code = ?",
                    tenantId,
                    code);
            return;
        }
        // The flag_code foreign key turns a typo into a failed write here rather than a capability
        // that is silently off forever — see V209 on why the registry is a table.
        jdbc.update(
                """
                INSERT INTO tenant_feature_overrides (tenant_id, flag_code, enabled, note, set_by)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, flag_code) DO UPDATE
                   SET enabled = excluded.enabled,
                       note = excluded.note,
                       set_by = excluded.set_by
                """,
                tenantId,
                code,
                enabled,
                note == null ? "" : note,
                by);
    }

    /**
     * What this tenant can actually do: its plan's features, with its own overrides applied.
     *
     * <p>An unlicensed tenant has no capabilities at all rather than its last plan's — the licence
     * is what the plan hangs from, so a lapsed one leaves nothing behind. Deliberately a set of
     * strings rather than a map: absent and false mean the same thing to every consumer, and having
     * two ways to say off is how they come to disagree.
     */
    @Transactional(readOnly = true)
    public Set<String> effectiveFlags(long tenantId) {
        List<String> codes =
                jdbc.queryForList(
                        """
                        WITH live AS (
                            SELECT l.plan_id
                              FROM tenant_licences l
                             WHERE l.tenant_id = ?
                               AND l.starts_at <= now() AND l.expires_at > now()
                             ORDER BY l.starts_at DESC, l.id DESC
                             LIMIT 1
                        ),
                        granted AS (
                            SELECT pf.flag_code FROM plan_features pf JOIN live ON live.plan_id = pf.plan_id
                        ),
                        overridden AS (
                            SELECT flag_code, enabled FROM tenant_feature_overrides WHERE tenant_id = ?
                        )
                        SELECT code FROM (
                            SELECT g.flag_code AS code FROM granted g
                             WHERE NOT EXISTS (SELECT 1 FROM overridden o WHERE o.flag_code = g.flag_code)
                            UNION
                            SELECT o.flag_code FROM overridden o WHERE o.enabled
                        ) resolved
                        ORDER BY code
                        """,
                        String.class,
                        tenantId,
                        tenantId);
        return new TreeSet<>(codes);
    }

    /** One tenant's overrides, for the screen that edits them. */
    @Transactional(readOnly = true)
    public List<Override> overrides(long tenantId) {
        return jdbc.query(
                """
                SELECT o.flag_code, o.enabled, o.note, a.display_name AS set_by
                  FROM tenant_feature_overrides o
                  LEFT JOIN platform_admins a ON a.id = o.set_by
                 WHERE o.tenant_id = ?
                 ORDER BY o.flag_code
                """,
                (rs, row) ->
                        new Override(
                                rs.getString("flag_code"),
                                rs.getBoolean("enabled"),
                                rs.getString("note"),
                                rs.getString("set_by")),
                tenantId);
    }

    /**
     * The licence to show a till: the one covering now, or failing that the most recent one.
     *
     * <p>{@link #current} answers "may this shop sync?" and empty is the whole answer. This answers
     * "what should this shop be told?", and there empty is nearly useless — a shop whose licence ran
     * out on Tuesday needs to see the plan it had and the date it ended, because that is a renewal
     * notice, whereas "unlicensed" on its own is a mystery the owner has to ring somebody about.
     *
     * <p>A live period always wins. Between dead ones the latest-ending wins, which reads correctly
     * in both directions: for a lapsed shop it is the period that just ended, and for one whose
     * renewal starts next month it is that renewal.
     */
    @Transactional(readOnly = true)
    public Optional<LicencedPlan> currentOrLast(long tenantId) {
        return jdbc
                .query(
                        """
                        SELECT p.code, p.name, p.max_terminals, p.max_users,
                               l.starts_at, l.expires_at,
                               (l.starts_at <= now() AND l.expires_at > now()) AS live
                          FROM tenant_licences l JOIN plans p ON p.id = l.plan_id
                         WHERE l.tenant_id = ?
                         ORDER BY (l.starts_at <= now() AND l.expires_at > now()) DESC,
                                  l.expires_at DESC, l.id DESC
                         LIMIT 1
                        """,
                        (rs, row) ->
                                new LicencedPlan(
                                        rs.getString("code"),
                                        rs.getString("name"),
                                        (Integer) rs.getObject("max_terminals"),
                                        (Integer) rs.getObject("max_users"),
                                        rs.getTimestamp("starts_at").toInstant(),
                                        rs.getTimestamp("expires_at").toInstant(),
                                        rs.getBoolean("live")),
                        tenantId)
                .stream()
                .findFirst();
    }

    private static String normalise(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static List<String> splitFlags(String aggregated) {
        return aggregated == null || aggregated.isBlank()
                ? List.of()
                : List.of(aggregated.split(","));
    }

    /**
     * A licence period together with the plan it was sold under, which is what a renewal notice
     * needs and what {@link Licence} alone cannot give — that record carries a plan <em>code</em>
     * and no name or limits, because the screens that read it already have the plan list loaded.
     *
     * @param live whether this period covers now. Computed by the same predicate the till's own
     *     authentication runs, in the same statement, so the two cannot disagree about a shop that
     *     lapses between two queries.
     */
    public record LicencedPlan(
            String code,
            String name,
            Integer maxTerminals,
            Integer maxUsers,
            Instant startsAt,
            Instant expiresAt,
            boolean live) {}

    public record Plan(
            long id,
            String code,
            String name,
            String description,
            long priceMinor,
            Integer maxTerminals,
            Integer maxUsers,
            boolean active,
            List<String> flags) {}

    public record FeatureFlag(String code, String name, String description) {}

    public record Licence(
            long id, String planCode, Instant startsAt, Instant expiresAt, String note) {}

    public record Override(String flagCode, boolean enabled, String note, String setBy) {}
}

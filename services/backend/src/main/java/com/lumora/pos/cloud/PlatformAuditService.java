package com.lumora.pos.cloud;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who did what, across shops (M4-08).
 *
 * <p>M3-08 gave the shop floor an audit trail on the principle that an action nobody is named for
 * is an action nobody can be asked about. These are the actions where that matters most: a platform
 * admin suspends a business, re-keys its till, or creates an owner login for a shop that is not
 * theirs.
 *
 * <p><b>Written in the caller's transaction, never its own.</b> {@link Propagation#MANDATORY} is
 * deliberate and will fail loudly if anybody calls this outside one. An audit row that can commit
 * while its action rolls back records something that did not happen; one that can roll back while
 * its action commits is worse, because the trail is then quietly incomplete and looks fine. Both
 * failures are invisible at the call site, so the wiring is asserted instead of trusted — the same
 * argument §A makes for the outbox row sharing the sale's transaction.
 */
@Service
@Profile("cloud")
public class PlatformAuditService {

    private final JdbcTemplate jdbc;

    public PlatformAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param adminId null only for the system itself — {@link PlatformBootstrap} creating the first
     *     account, when by definition no admin exists to attribute it to.
     * @param tenantId null for acts that are not about one shop.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long adminId, String action, Long tenantId, Map<String, ?> detail) {
        jdbc.update(
                """
                INSERT INTO platform_audit (platform_admin_id, action, tenant_id, detail)
                VALUES (?, ?, ?, ?::jsonb)
                """,
                adminId,
                action,
                tenantId,
                toJson(detail));
    }

    @Transactional(readOnly = true)
    public List<Entry> recent(int limit) {
        return jdbc.query(
                """
                SELECT p.id, p.action, p.tenant_id, t.name AS tenant_name,
                       p.detail::text AS detail, p.at,
                       a.display_name AS actor, a.email AS actor_email
                  FROM platform_audit p
                  LEFT JOIN platform_admins a ON a.id = p.platform_admin_id
                  LEFT JOIN tenants t ON t.id = p.tenant_id
                 ORDER BY p.at DESC
                 LIMIT ?
                """,
                (rs, row) ->
                        new Entry(
                                rs.getLong("id"),
                                rs.getString("action"),
                                (Long) rs.getObject("tenant_id"),
                                rs.getString("tenant_name"),
                                rs.getString("detail"),
                                // The system writes rows with no admin. Naming it beats a blank
                                // cell that reads like a bug in the screen.
                                rs.getString("actor") == null ? "system" : rs.getString("actor"),
                                rs.getString("actor_email"),
                                rs.getTimestamp("at").toInstant()),
                Math.min(Math.max(limit, 1), 200));
    }

    /**
     * A deliberately small JSON writer.
     *
     * <p>Jackson is on the classpath and would do this in one line. It is not used because this is
     * a detail bag written on a write path: an unserialisable value would throw <em>inside</em> the
     * transaction doing the actual work and roll back a legitimate tenant creation because its
     * audit note was awkward. Flat string values cannot fail, and every caller here passes those.
     */
    private static String toJson(Map<String, ?> detail) {
        if (detail == null || detail.isEmpty()) {
            return "{}";
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : new LinkedHashMap<>(detail).entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(quote(entry.getKey())).append(':');
            Object value = entry.getValue();
            if (value == null) {
                json.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value);
            } else {
                json.append(quote(value.toString()));
            }
        }
        return json.append('}').toString();
    }

    private static String quote(String raw) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * @param actor the admin's display name, or {@code "system"} for a row the process wrote.
     * @param actorEmail the identity that is actually unique, and the reason both are carried. Two
     *     colleagues can share a display name, and a trail that says only "Estate Staff" cannot
     *     answer the question an audit is for. Null for a system row, which has no account behind
     *     it to name.
     */
    public record Entry(
            long id,
            String action,
            Long tenantId,
            String tenantName,
            String detail,
            String actor,
            String actorEmail,
            Instant at) {}
}

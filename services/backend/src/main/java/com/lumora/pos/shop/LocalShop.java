package com.lumora.pos.shop;

import com.lumora.pos.web.RejectedException;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Who this shop PC is.
 *
 * <p>A desktop database holds exactly one tenant — that is what "desktop" means — and branch codes
 * are only unique within a tenant, so every write path needs the same two lookups before it can do
 * anything. Until M2 that logic lived privately inside {@code SaleService}; shifts, cash movements
 * and refunds all needed it too, and four copies of "assert there is exactly one tenant" is three
 * chances for one of them to quietly assume instead.
 *
 * <p>The single-tenant invariant is <em>asserted</em> rather than assumed. A second tenant on a
 * till means something upstream is wrong, and finding that out at the next sale with a confusing
 * error is worse than finding out now.
 *
 * <p>When the cloud needs this logic (M4-01) the tenant comes from the request's authenticated
 * context instead. This class is the seam where that changes.
 */
@Component
public class LocalShop {

    private final JdbcTemplate jdbc;

    public LocalShop(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long soleTenantId() {
        List<Long> tenantIds = jdbc.queryForList("SELECT id FROM tenants ORDER BY id", Long.class);
        if (tenantIds.size() != 1) {
            throw new IllegalStateException(
                    "A desktop database must contain exactly one tenant, found "
                            + tenantIds.size()
                            + ". Reset it with `pnpm db:reset` and reseed with `pnpm db:seed`.");
        }
        return tenantIds.get(0);
    }

    public Branch branch(String branchCode) {
        long tenantId = soleTenantId();
        try {
            return jdbc.queryForObject(
                    "SELECT id, tenant_id, code FROM branches WHERE tenant_id = ? AND code = ?",
                    (rs, row) -> new Branch(rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("code")),
                    tenantId,
                    branchCode);
        } catch (EmptyResultDataAccessException e) {
            throw new RejectedException("Unknown branch code: " + branchCode);
        }
    }

    public record Branch(long id, long tenantId, String code) {}
}

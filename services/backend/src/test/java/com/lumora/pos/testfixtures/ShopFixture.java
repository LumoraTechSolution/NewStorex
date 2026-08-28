package com.lumora.pos.testfixtures;

import com.lumora.pos.user.Role;
import com.lumora.pos.user.UserService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A shop to test against: the one tenant, a branch of its own, and two products.
 *
 * <p>Each call makes a <em>new branch</em> under the same tenant, because a desktop database must
 * hold exactly one tenant and {@code LocalShop} asserts that. Per-branch isolation is what lets
 * these tests run in any order without a shift opened by one interfering with a sale rung up by
 * another — the {@code ux_shifts_one_open_per_terminal} index is scoped to (tenant, branch,
 * terminal), so a fresh branch is a fresh terminal.
 *
 * <p>A component rather than a static helper so it can use {@link UserService} to hash the PINs.
 * Writing a hardcoded BCrypt string into the fixture would tie the tests to a particular cost
 * factor and to whatever produced the constant, and would silently stop testing anything the day
 * the encoder changed.
 *
 * <p>Both a manager and a cashier are seeded (M3-08). The cashier exists so that every permission
 * gate has something that fails it: a fixture with only privileged users lets a refusal path go
 * untested while every assertion still passes.
 */
@Component
public class ShopFixture {

    /** The PIN every seeded user shares. Matched against the hash this fixture actually produces. */
    public static final String MANAGER_PIN = "4821";

    /** Holds AUTHORISE_REFUND and RUN_SHIFT. The code most tests act as. */
    public static final String MANAGER_CODE = "MGR";

    /** Holds neither. The user that permission-gate tests expect to be refused. */
    public static final String CASHIER_CODE = "TILL";

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    /**
     * A desktop database holds exactly one tenant, and {@code LocalShop} asserts it — so this is
     * the <em>same</em> uuid {@code SaleCommitTest} seeds with, not a second one. Two fixtures
     * each inventing their own tenant makes every test in the run fail with "found 2", which is
     * exactly what the assertion is for.
     */
    public static final UUID SOLE_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

    private final JdbcTemplate jdbc;
    private final UserService users;

    public ShopFixture(JdbcTemplate jdbc, UserService users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public Shop seed() {
        int n = UNIQUE.incrementAndGet();
        String branchCode = "S%02d".formatted(n);

        jdbc.update(
                """
                INSERT INTO tenants (client_uuid, name) VALUES (?, 'Kandy Stores')
                ON CONFLICT (client_uuid) DO NOTHING
                """,
                SOLE_TENANT);
        long tenantId =
                jdbc.queryForObject("SELECT id FROM tenants WHERE client_uuid = ?", Long.class, SOLE_TENANT);

        long branchId =
                jdbc.queryForObject(
                        """
                        INSERT INTO branches (client_uuid, tenant_id, code, name)
                        VALUES (?, ?, ?, ?) RETURNING id
                        """,
                        Long.class,
                        UUID.randomUUID(),
                        tenantId,
                        branchCode,
                        "Branch " + n);

        UUID productUuid = insertProduct(tenantId, n, "Tea 400g", 45_000, 1800, "");
        // Zero-rated, so a basket can genuinely mix treatments (M1-18) and a refund can be
        // checked against a line whose tax is zero.
        UUID exemptUuid = insertProduct(tenantId, n, "Bread 450g", 25_000, 0, "-X");

        // Once per tenant, not once per branch: user codes are unique per tenant and every
        // seed() call shares the one tenant a desktop database is allowed.
        long managerId = ensureUser(tenantId, MANAGER_CODE, "Fixture Manager", Role.MANAGER);
        long cashierId = ensureUser(tenantId, CASHIER_CODE, "Fixture Cashier", Role.CASHIER);

        return new Shop(
                tenantId, branchId, branchCode, productUuid, exemptUuid, managerId, cashierId);
    }

    private long ensureUser(long tenantId, String code, String name, Role role) {
        List<Long> existing =
                jdbc.queryForList(
                        "SELECT id FROM users WHERE tenant_id = ? AND code = ?",
                        Long.class,
                        tenantId,
                        code);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return users.create(tenantId, UUID.randomUUID(), code, name, role, MANAGER_PIN).id();
    }

    private UUID insertProduct(long tenantId, int n, String name, long priceMinor, int rateBp, String suffix) {
        UUID uuid = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO products (client_uuid, tenant_id, sku, name, price_minor, tax_mode, tax_rate_bp)
                VALUES (?, ?, ?, ?, ?, 'INCLUSIVE', ?)
                """,
                uuid,
                tenantId,
                "FIX-%03d%s".formatted(n, suffix),
                name,
                priceMinor,
                rateBp);
        return uuid;
    }

    public record Shop(
            long tenantId,
            long branchId,
            String branchCode,
            UUID productUuid,
            UUID exemptUuid,
            long managerId,
            long cashierId) {}
}

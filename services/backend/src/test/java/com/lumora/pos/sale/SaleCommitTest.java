package com.lumora.pos.sale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The M0 spike's whole point: a sale commits locally, completely, and carries its own sync record.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}. These tests need {@link SaleService}'s
 * own transaction to really commit and really roll back — a test-managed transaction wrapping it
 * would mask exactly the behaviour under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class SaleCommitTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    /** A desktop database holds exactly one tenant. Every test in this class shares it. */
    private static final UUID SOLE_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

    @Autowired SaleService sales;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aSaleWritesItsLinesMovementsAndOutboxRowTogether() {
        Fixture fixture = seed();
        UUID saleUuid = UUID.randomUUID();

        SaleResponse response = sales.commit(request(saleUuid, fixture, 2, 45000));

        assertThat(response.alreadyExisted()).isFalse();
        assertThat(response.invoiceNumber()).isEqualTo(fixture.branchCode() + "-T1-000001");
        assertThat(response.totalMinor()).isEqualTo(90000);

        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", saleUuid)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM sale_items WHERE sale_id = ?", response.id())).isEqualTo(1);

        // Stock left the shelf: one negative movement, linked back to the sale.
        Integer qtyDelta =
                jdbc.queryForObject(
                        "SELECT qty_delta FROM stock_movements WHERE ref_type = 'sale' AND ref_id = ?",
                        Integer.class,
                        response.id());
        assertThat(qtyDelta).isEqualTo(-2);

        // And the sync record exists, unacked, keyed on the same uuid the cloud upserts on.
        assertThat(count("SELECT count(*) FROM outbox WHERE aggregate = 'sale' AND aggregate_id = ? AND acked_at IS NULL", saleUuid))
                .isEqualTo(1);
    }

    @Test
    void theOutboxPayloadCarriesEnoughToRebuildTheSale() {
        Fixture fixture = seed();
        UUID saleUuid = UUID.randomUUID();
        sales.commit(request(saleUuid, fixture, 1, 45000));

        // Queried through jsonb operators rather than string matching: what matters is that the
        // cloud can read these fields, not how Postgres chose to lay the text out.
        assertThat(jsonField(saleUuid, "invoiceNumber")).isEqualTo(fixture.branchCode() + "-T1-000001");
        assertThat(jsonField(saleUuid, "totalMinor")).isEqualTo("45000");
        assertThat(jsonField(saleUuid, "taxRateBp")).isEqualTo("1800");
        assertThat(jsonField(saleUuid, "taxMode")).isEqualTo("INCLUSIVE");
        assertThat(jsonField(saleUuid, "clientUuid")).isEqualTo(saleUuid.toString());

        // The rate is stamped on the payload, so a receipt reprinted after a VAT change
        // still reproduces the sale as it was rung up.
        String firstLineProduct =
                jdbc.queryForObject(
                        "SELECT payload->'lines'->0->>'productClientUuid' FROM outbox WHERE aggregate_id = ?",
                        String.class,
                        saleUuid);
        assertThat(firstLineProduct).isEqualTo(fixture.productUuid().toString());
    }

    /**
     * The guarantee the architecture rests on: a sale can never exist without its sync record. If
     * the outbox write fails, the sale must not survive either.
     */
    @Test
    void aFailedOutboxWriteTakesTheWholeSaleWithIt() {
        Fixture fixture = seed();
        UUID saleUuid = UUID.randomUUID();

        breakOutboxInserts();
        try {
            assertThatThrownBy(() -> sales.commit(request(saleUuid, fixture, 1, 45000)))
                    .isInstanceOf(Exception.class);
        } finally {
            repairOutboxInserts();
        }

        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", saleUuid))
                .as("the sale must have rolled back with its outbox row")
                .isZero();
        assertThat(count("SELECT count(*) FROM stock_movements WHERE ref_type = 'sale' AND ref_id IN (SELECT id FROM sales WHERE client_uuid = ?)", saleUuid))
                .as("no orphan stock movement")
                .isZero();
    }

    @Test
    void resendingTheSameSaleReturnsTheOriginalAndDoesNotDuplicateIt() {
        Fixture fixture = seed();
        UUID saleUuid = UUID.randomUUID();

        SaleResponse first = sales.commit(request(saleUuid, fixture, 1, 45000));
        SaleResponse retry = sales.commit(request(saleUuid, fixture, 1, 45000));

        assertThat(retry.alreadyExisted()).isTrue();
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(retry.invoiceNumber()).isEqualTo(first.invoiceNumber());

        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", saleUuid)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox WHERE aggregate_id = ?", saleUuid))
                .as("a retry must not enqueue the sale twice")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM stock_movements WHERE ref_type = 'sale' AND ref_id = ?", first.id()))
                .as("a retry must not move stock twice")
                .isEqualTo(1);
    }

    @Test
    void invoiceNumbersRunPerTerminalAndDoNotCollide() {
        Fixture fixture = seed();

        SaleResponse t1a = sales.commit(requestOn(UUID.randomUUID(), fixture, "T1"));
        SaleResponse t1b = sales.commit(requestOn(UUID.randomUUID(), fixture, "T1"));
        SaleResponse t2a = sales.commit(requestOn(UUID.randomUUID(), fixture, "T2"));

        assertThat(t1a.invoiceNumber()).isEqualTo(fixture.branchCode() + "-T1-000001");
        assertThat(t1b.invoiceNumber()).isEqualTo(fixture.branchCode() + "-T1-000002");
        // T2 counts on its own — that is what lets terminals issue numbers offline
        // without coordinating.
        assertThat(t2a.invoiceNumber()).isEqualTo(fixture.branchCode() + "-T2-000001");
    }

    @Test
    void totalsThatDoNotAddUpAreRejected() {
        Fixture fixture = seed();

        CreateSaleRequest wrong =
                new CreateSaleRequest(
                        UUID.randomUUID(),
                        fixture.branchCode(),
                        "T1",
                        null,
                        "INCLUSIVE",
                        1800,
                        45000L,
                        0L,
                        6864L,
                        99999L, // does not equal subtotal - discount
                        List.of(new CreateSaleRequest.Line(fixture.productUuid(), 1, 45000L, 0L, 6864L, 45000L)));

        assertThatThrownBy(() -> sales.commit(wrong))
                .isInstanceOf(SaleRejectedException.class)
                .hasMessageContaining("is not totalMinor");
    }

    /**
     * A shop PC holding two tenants means something upstream went wrong. Failing loudly at the next
     * sale beats silently picking one of them and attributing a shop's takings to the wrong owner.
     */
    @Test
    void aSecondTenantOnAShopPcIsRefusedRatherThanGuessedAt() {
        Fixture fixture = seed();
        UUID intruder = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (client_uuid, name) VALUES (?, 'Somebody Else')", intruder);

        try {
            assertThatThrownBy(() -> sales.commit(request(UUID.randomUUID(), fixture, 1, 45000)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exactly one tenant");
        } finally {
            jdbc.update("DELETE FROM tenants WHERE client_uuid = ?", intruder);
        }
    }

    @Test
    void anUnknownProductIsRejected() {
        Fixture fixture = seed();
        UUID ghost = UUID.randomUUID();

        CreateSaleRequest request =
                new CreateSaleRequest(
                        UUID.randomUUID(),
                        fixture.branchCode(),
                        "T1",
                        null,
                        "INCLUSIVE",
                        1800,
                        45000L,
                        0L,
                        6864L,
                        45000L,
                        List.of(new CreateSaleRequest.Line(ghost, 1, 45000L, 0L, 6864L, 45000L)));

        assertThatThrownBy(() -> sales.commit(request))
                .isInstanceOf(SaleRejectedException.class)
                .hasMessageContaining("Unknown product");
    }

    // ------------------------------------------------------------------- helpers

    private CreateSaleRequest request(UUID saleUuid, Fixture fixture, int qty, long unitPrice) {
        long lineTotal = unitPrice * qty;
        return new CreateSaleRequest(
                saleUuid,
                fixture.branchCode(),
                "T1",
                null,
                "INCLUSIVE",
                1800,
                lineTotal,
                0L,
                lineTotal * 1800 / 11800, // VAT extracted from an inclusive price
                lineTotal,
                List.of(
                        new CreateSaleRequest.Line(
                                fixture.productUuid(), qty, unitPrice, 0L, lineTotal * 1800 / 11800, lineTotal)));
    }

    private CreateSaleRequest requestOn(UUID saleUuid, Fixture fixture, String terminalCode) {
        CreateSaleRequest base = request(saleUuid, fixture, 1, 45000);
        return new CreateSaleRequest(
                base.clientUuid(),
                base.branchCode(),
                terminalCode,
                base.soldAt(),
                base.taxMode(),
                base.taxRateBp(),
                base.subtotalMinor(),
                base.discountMinor(),
                base.taxMinor(),
                base.totalMinor(),
                base.lines());
    }

    private Integer count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private String jsonField(UUID aggregateId, String field) {
        return jdbc.queryForObject(
                "SELECT payload->>? FROM outbox WHERE aggregate_id = ?", String.class, field, aggregateId);
    }

    private void breakOutboxInserts() {
        jdbc.execute(
                """
                CREATE OR REPLACE FUNCTION fail_outbox_insert() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'simulated outbox failure'; END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute(
                "CREATE TRIGGER trg_fail_outbox BEFORE INSERT ON outbox"
                        + " FOR EACH ROW EXECUTE FUNCTION fail_outbox_insert()");
    }

    private void repairOutboxInserts() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_outbox ON outbox");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_outbox_insert()");
    }

    /**
     * One tenant for the whole class, then a unique branch and product per test.
     *
     * <p>The single tenant is not tidiness — it is the invariant a desktop database holds, and
     * {@code SaleService} now asserts it. Branch and product stay unique per test because this
     * class commits for real and would otherwise collide with its own leftovers.
     */
    private Fixture seed() {
        int n = UNIQUE.incrementAndGet();
        String branchCode = "B%02d".formatted(n);

        jdbc.update(
                """
                INSERT INTO tenants (client_uuid, name) VALUES (?, 'Kandy Stores')
                ON CONFLICT (client_uuid) DO NOTHING
                """,
                SOLE_TENANT);
        long tenantId =
                jdbc.queryForObject(
                        "SELECT id FROM tenants WHERE client_uuid = ?", Long.class, SOLE_TENANT);

        jdbc.update(
                "INSERT INTO branches (client_uuid, tenant_id, code, name) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(),
                tenantId,
                branchCode,
                "Branch " + n);

        UUID productUuid = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO products (client_uuid, tenant_id, sku, name, price_minor, tax_mode, tax_rate_bp)
                VALUES (?, ?, ?, 'Tea 400g', 45000, 'INCLUSIVE', 1800)
                """,
                productUuid,
                tenantId,
                "SKU-%03d".formatted(n));

        return new Fixture(tenantId, branchCode, productUuid);
    }

    private record Fixture(long tenantId, String branchCode, UUID productUuid) {}
}

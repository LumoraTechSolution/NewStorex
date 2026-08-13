package com.lumora.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Structural guards on the things ROADMAP §A says must never be compromised.
 *
 * <p>These assert properties of the schema rather than behaviour of a feature, because the failure
 * modes they cover are the kind that get introduced innocently — someone adds a
 * {@code quantity_on_hand} column to make a query faster, and offline reconciliation quietly stops
 * being conflict-free.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
@Transactional
class MinimalSchemaTest {

    @Autowired JdbcTemplate jdbc;

    // ---------------------------------------------------------------- idempotency

    @ParameterizedTest
    @ValueSource(strings = {"tenants", "branches", "products", "sales", "stock_movements"})
    void everyAggregateRootHasAUniqueClientUuid(String table) {
        Boolean unique =
                jdbc.queryForObject(
                        """
                        SELECT exists(
                          SELECT 1
                          FROM pg_index i
                          JOIN pg_class t ON t.oid = i.indrelid
                          JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (i.indkey)
                          WHERE t.relname = ?
                            AND a.attname = 'client_uuid'
                            AND i.indisunique
                        )
                        """,
                        Boolean.class,
                        table);
        assertThat(unique)
                .as("%s must have a unique index on client_uuid — the cloud upserts on it", table)
                .isTrue();

        Boolean notNull =
                jdbc.queryForObject(
                        """
                        SELECT a.attnotnull
                        FROM pg_attribute a
                        JOIN pg_class t ON t.oid = a.attrelid
                        WHERE t.relname = ? AND a.attname = 'client_uuid'
                        """,
                        Boolean.class,
                        table);
        assertThat(notNull).as("%s.client_uuid must be NOT NULL", table).isTrue();
    }

    @Test
    void redeliveringTheSameAggregateIsRejected() {
        UUID duplicate = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (client_uuid, name) VALUES (?, ?)", duplicate, "Kandy Stores");

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO tenants (client_uuid, name) VALUES (?, ?)",
                                        duplicate,
                                        "Kandy Stores (retry)"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ------------------------------------------------------- movements, not balances

    @Test
    void noTableStoresAStockLevel() {
        List<String> offenders =
                jdbc.queryForList(
                        """
                        SELECT table_name || '.' || column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND (
                            column_name IN ('quantity_on_hand', 'qty_on_hand', 'stock_level',
                                            'stock_on_hand', 'balance', 'current_stock')
                            OR column_name LIKE '%_balance'
                          )
                        """,
                        String.class);

        assertThat(offenders)
                .as(
                        "Stock and money levels are the SUM of movements, never a stored column. "
                                + "If a rollup is needed for speed, make it a materialised view — "
                                + "something the database maintains, not something a writer can forget.")
                .isEmpty();
    }

    // ------------------------------------------------------------------------- money

    @Test
    void everyMoneyColumnIsAnInteger() {
        List<String> offenders =
                jdbc.queryForList(
                        """
                        SELECT table_name || '.' || column_name || ' is ' || data_type
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND column_name LIKE '%\\_minor'
                          AND data_type NOT IN ('bigint', 'integer')
                        """,
                        String.class);

        assertThat(offenders)
                .as("Money is integer minor units. No float, no double, no numeric.")
                .isEmpty();
    }

    @Test
    void moneyColumnsRejectNegativeAmounts() {
        long tenantId = seedTenant();

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO products
                                          (client_uuid, tenant_id, sku, name, price_minor, tax_mode, tax_rate_bp)
                                        VALUES (?, ?, 'SKU-1', 'Tea 400g', -1, 'INCLUSIVE', 1800)
                                        """,
                                        UUID.randomUUID(),
                                        tenantId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stockMovementsRejectANoOpDelta() {
        long tenantId = seedTenant();
        long branchId = seedBranch(tenantId);
        long productId = seedProduct(tenantId);

        // A movement of zero says nothing happened, which is not a thing that happened.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO stock_movements
                                          (client_uuid, tenant_id, branch_id, product_id, qty_delta, reason, created_by)
                                        VALUES (?, ?, ?, ?, 0, 'SALE', 1)
                                        """,
                                        UUID.randomUUID(),
                                        tenantId,
                                        branchId,
                                        productId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stockMovementsRejectAnUnknownReason() {
        long tenantId = seedTenant();
        long branchId = seedBranch(tenantId);
        long productId = seedProduct(tenantId);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO stock_movements
                                          (client_uuid, tenant_id, branch_id, product_id, qty_delta, reason, created_by)
                                        VALUES (?, ?, ?, ?, -1, 'SHRINKAGE', 1)
                                        """,
                                        UUID.randomUUID(),
                                        tenantId,
                                        branchId,
                                        productId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------------ outbox

    @Test
    void theOutboxDrainIndexIsPartial() {
        String definition =
                jdbc.queryForObject(
                        "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_outbox_pending'",
                        String.class);

        assertThat(definition)
                .as(
                        "The drain scans unacked rows only. Without the WHERE clause the index grows "
                                + "with the shop's entire history instead of with the backlog.")
                .contains("WHERE (acked_at IS NULL)");
    }

    @Test
    void anOutboxRowCanBeWrittenAlongsideItsAggregate() {
        UUID saleUuid = UUID.randomUUID();
        long tenantId = seedTenant();

        jdbc.update(
                """
                INSERT INTO outbox (tenant_id, aggregate, aggregate_id, payload)
                VALUES (?, 'sale', ?, ?::jsonb)
                """,
                tenantId,
                saleUuid,
                "{\"total_minor\": 45000}");

        Integer pending =
                jdbc.queryForObject(
                        "SELECT count(*) FROM outbox WHERE acked_at IS NULL AND aggregate_id = ?",
                        Integer.class,
                        saleUuid);
        assertThat(pending).isEqualTo(1);
    }

    private long seedTenant() {
        return jdbc.queryForObject(
                "INSERT INTO tenants (client_uuid, name) VALUES (?, 'Kandy Stores') RETURNING id",
                Long.class,
                UUID.randomUUID());
    }

    private long seedBranch(long tenantId) {
        return jdbc.queryForObject(
                """
                INSERT INTO branches (client_uuid, tenant_id, code, name)
                VALUES (?, ?, 'KND', 'Kandy') RETURNING id
                """,
                Long.class,
                UUID.randomUUID(),
                tenantId);
    }

    private long seedProduct(long tenantId) {
        return jdbc.queryForObject(
                """
                INSERT INTO products
                  (client_uuid, tenant_id, sku, name, price_minor, tax_mode, tax_rate_bp)
                VALUES (?, ?, 'SKU-1', 'Tea 400g', 45000, 'INCLUSIVE', 1800) RETURNING id
                """,
                Long.class,
                UUID.randomUUID(),
                tenantId);
    }
}

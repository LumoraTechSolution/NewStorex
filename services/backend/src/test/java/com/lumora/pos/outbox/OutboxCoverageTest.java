package com.lumora.pos.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.lumora.pos.customer.CustomerService;
import com.lumora.pos.product.ProductAdminService;
import com.lumora.pos.product.ProductAdminService.ProductDraft;
import com.lumora.pos.product.ProductRow;
import com.lumora.pos.sale.CreateSaleRequest;
import com.lumora.pos.sale.SaleResponse;
import com.lumora.pos.sale.SaleService;
import com.lumora.pos.shift.DenominationCount;
import com.lumora.pos.shift.OpenShiftRequest;
import com.lumora.pos.shift.ShiftService;
import com.lumora.pos.stock.GoodsReceiptService;
import com.lumora.pos.testfixtures.ShopFixture;
import com.lumora.pos.user.Role;
import com.lumora.pos.user.UserService;
import com.lumora.pos.user.UserService.UserRow;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Everything the shop writes has a way to the cloud (M3-12).
 *
 * <h2>What this class is guarding</h2>
 *
 * §A rule 1 is that the outbox row is written in the same transaction as the domain rows. Each
 * write path's own test already checks its own aggregate. What none of them can check is the thing
 * that actually goes wrong: a <em>new</em> write path added later that quietly writes rows nothing
 * ever ships. The failure is silent on the till, invisible in the logs, and discovered by an owner
 * whose console is missing a week of price changes.
 *
 * <h2>The movement audit is the important one</h2>
 *
 * `M3-12` lists {@code movement} as an aggregate to add. It turned out already to be covered, and
 * covered in a way worth protecting rather than duplicating: every stock movement travels inside
 * the document that caused it — a sale, a refund, a goods receipt, an adjustment, a stocktake. A
 * separate {@code movement} aggregate would mean the same movement reaching the cloud twice, which
 * on a table whose whole meaning is {@code Σ qty_delta} doubles a shop's stock. So this asserts
 * coverage instead of adding a second path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class OutboxCoverageTest {

    @Autowired ProductAdminService products;
    @Autowired CustomerService customers;
    @Autowired UserService users;
    @Autowired SaleService sales;
    @Autowired GoodsReceiptService receipts;
    @Autowired ShiftService shifts;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    // ------------------------------------------------------------------------- product

    @Test
    void creatingAProductEnqueuesIt() {
        ShopFixture.Shop shop = fixtures.seed();
        UUID uuid = UUID.randomUUID();

        products.create(shop.tenantId(), draft(uuid, "Jam 340g", 62_000));

        assertThat(enqueued("product", uuid)).isEqualTo(1);
        assertThat(payload(uuid, "priceMinor")).isEqualTo("62000");
        assertThat(payload(uuid, "name")).isEqualTo("Jam 340g");
    }

    /**
     * A repricing enqueues the row again, whole.
     *
     * <p>Two outbox rows for one product is correct and not a duplicate: each carries the state at
     * the moment it was written, the cloud upserts on {@code client_uuid}, and whichever lands
     * second stands. Shipping a diff instead would make delivery order load-bearing, which an
     * offline shop's backlog cannot promise.
     */
    @Test
    void repricingAProductEnqueuesTheWholeRowAgain() {
        ShopFixture.Shop shop = fixtures.seed();
        UUID uuid = UUID.randomUUID();
        ProductRow created = products.create(shop.tenantId(), draft(uuid, "Jam 340g", 62_000));

        products.save(shop.tenantId(), created.id(), draft(uuid, "Jam 340g", 69_000));

        assertThat(enqueued("product", uuid)).isEqualTo(2);
        assertThat(latestPayload(uuid, "priceMinor")).isEqualTo("69000");
    }

    @Test
    void discontinuingAProductEnqueuesIt() {
        ShopFixture.Shop shop = fixtures.seed();
        UUID uuid = UUID.randomUUID();
        ProductRow created = products.create(shop.tenantId(), draft(uuid, "Old line", 10_000));

        products.setActive(shop.tenantId(), created.id(), false);

        assertThat(enqueued("product", uuid)).isEqualTo(2);
        assertThat(latestPayload(uuid, "active")).isEqualTo("false");
    }

    // ---------------------------------------------------------------------------- user

    @Test
    void creatingAndPromotingAUserEnqueuesThemAndNeverTheirHash() {
        ShopFixture.Shop shop = fixtures.seed();
        UUID uuid = UUID.randomUUID();
        String code = "OBX" + UNIQUE.incrementAndGet();

        UserRow created =
                users.create(shop.tenantId(), uuid, code, "Outbox probe", Role.CASHIER, "4821");
        users.update(shop.tenantId(), created.id(), "Outbox probe", Role.SUPERVISOR);

        assertThat(enqueued("user", uuid)).isEqualTo(2);
        assertThat(latestPayload(uuid, "role")).isEqualTo("SUPERVISOR");

        // The credential never reaches the queue. Asserted on the raw JSON rather than on named
        // fields, because the failure this guards against is somebody adding a field, not
        // renaming one — and a hash on a queue is a hash in a backup, a log and a request body.
        String raw = rawPayload(uuid);
        assertThat(raw).doesNotContain("pin");
        assertThat(raw).doesNotContain("$2a$");
        assertThat(raw).doesNotContain("4821");
    }

    /**
     * Resetting a PIN enqueues nothing, and that is the design.
     *
     * <p>The cloud holds no credential, so nothing it stores about this person has changed. A row
     * here would ship state whose only difference is invisible to the receiver — and it would put a
     * PIN change on a queue, one careless payload edit away from putting the PIN itself on it.
     */
    @Test
    void resettingAPinEnqueuesNothing() {
        ShopFixture.Shop shop = fixtures.seed();
        UUID uuid = UUID.randomUUID();
        String code = "PIN" + UNIQUE.incrementAndGet();
        UserRow created =
                users.create(shop.tenantId(), uuid, code, "PIN probe", Role.CASHIER, "4821");

        users.setPin(shop.tenantId(), created.id(), "1357");

        assertThat(enqueued("user", uuid)).isEqualTo(1);
    }

    // ------------------------------------------------------------------------ customer

    @Test
    void creatingAndEditingACustomerEnqueuesThemWithoutTheirNoteOrEmail() {
        ShopFixture.Shop shop = fixtures.seed();
        UUID uuid = UUID.randomUUID();
        String phone = "07" + (800_000 + UNIQUE.incrementAndGet());

        CustomerService.CustomerRow created =
                customers.create(
                        shop.tenantId(),
                        uuid,
                        "Outbox Customer",
                        phone,
                        "someone@example.com",
                        "prefers the small loaf",
                        shop.managerId());
        customers.setActive(shop.tenantId(), created.id(), false);

        assertThat(enqueued("customer", uuid)).isEqualTo(2);
        assertThat(latestPayload(uuid, "active")).isEqualTo("false");

        String raw = rawPayload(uuid);
        assertThat(raw).contains(phone);
        assertThat(raw).doesNotContain("someone@example.com");
        assertThat(raw).doesNotContain("small loaf");
    }

    // ------------------------------------------------------------------------ movements

    /**
     * Every stock movement this shop writes reaches the cloud inside some document.
     *
     * <p>The audit is deliberately blunt: exercise the write paths, then check that no movement
     * written during this test is absent from the outbox. It catches the thing that actually
     * happens — a new movement writer added later with no sync — without pretending to enumerate
     * paths a future change might add.
     */
    @Test
    void everyMovementWrittenHereTravelsInsideADocument() {
        ShopFixture.Shop shop = fixtures.seed();
        shifts.open(
                new OpenShiftRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,
                        null,
                        500_000L,
                        List.of(new DenominationCount(100_000L, 5))));

        long before = maxMovementId();

        SaleResponse sale = ringUp(shop);
        assertThat(sale.id()).isPositive();

        receipts.receive(
                shop.tenantId(),
                shop.branchCode(),
                UUID.randomUUID(),
                supplierId(shop),
                "DN-OBX-" + UNIQUE.incrementAndGet(),
                null,
                List.of(new GoodsReceiptService.ReceiptLine(shop.productUuid(), 6, 30_000L)),
                users.authenticate(
                        shop.tenantId(), ShopFixture.MANAGER_CODE, ShopFixture.MANAGER_PIN));

        // Every movement this test caused, and whether its uuid appears anywhere on the queue.
        List<UUID> orphans =
                jdbc.queryForList(
                        """
                        SELECT m.client_uuid
                          FROM stock_movements m
                         WHERE m.id > ?
                           AND NOT EXISTS (
                                 SELECT 1 FROM outbox o
                                  WHERE o.payload::text LIKE '%' || m.client_uuid::text || '%')
                        """,
                        UUID.class,
                        before);

        assertThat(orphans)
                .as("movements written with no outbox row carrying them")
                .isEmpty();
        assertThat(maxMovementId()).isGreaterThan(before);
    }

    // ------------------------------------------------------------------------- helpers

    private ProductDraft draft(UUID uuid, String name, long priceMinor) {
        return new ProductDraft(
                uuid,
                "OBX-" + Integer.toHexString(uuid.hashCode()),
                name,
                priceMinor,
                "INCLUSIVE",
                1800,
                null,
                List.of());
    }

    private long supplierId(ShopFixture.Shop shop) {
        List<Long> existing =
                jdbc.queryForList(
                        "SELECT id FROM suppliers WHERE tenant_id = ? ORDER BY id LIMIT 1",
                        Long.class,
                        shop.tenantId());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return jdbc.queryForObject(
                "INSERT INTO suppliers (client_uuid, tenant_id, name) VALUES (?, ?, 'Outbox Supplier') RETURNING id",
                Long.class,
                UUID.randomUUID(),
                shop.tenantId());
    }

    private SaleResponse ringUp(ShopFixture.Shop shop) {
        long total = 45_000;
        long tax = total * 1800 / 11800;
        long rounding = Math.floorDiv(total + 50, 100) * 100 - total;
        return sales.commit(
                new CreateSaleRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        null,
                        "INCLUSIVE",
                        1800,
                        total,
                        0L,
                        tax,
                        total,
                        List.of(
                                new CreateSaleRequest.Line(
                                        shop.productUuid(), 1, total, 0L, tax, total)),
                        rounding,
                        0L,
                        List.of(new CreateSaleRequest.Tender("CASH", total + rounding)),
                        null));
    }

    private long maxMovementId() {
        Long id = jdbc.queryForObject("SELECT COALESCE(max(id), 0) FROM stock_movements", Long.class);
        return id == null ? 0 : id;
    }

    private int enqueued(String aggregate, UUID aggregateId) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM outbox WHERE aggregate = ? AND aggregate_id = ?",
                        Integer.class,
                        aggregate,
                        aggregateId);
        return n == null ? 0 : n;
    }

    private String payload(UUID aggregateId, String field) {
        return jdbc.queryForObject(
                "SELECT payload ->> ? FROM outbox WHERE aggregate_id = ? ORDER BY id LIMIT 1",
                String.class,
                field,
                aggregateId);
    }

    private String latestPayload(UUID aggregateId, String field) {
        return jdbc.queryForObject(
                "SELECT payload ->> ? FROM outbox WHERE aggregate_id = ? ORDER BY id DESC LIMIT 1",
                String.class,
                field,
                aggregateId);
    }

    private String rawPayload(UUID aggregateId) {
        return String.join(
                "\n",
                jdbc.queryForList(
                        "SELECT payload::text FROM outbox WHERE aggregate_id = ? ORDER BY id",
                        String.class,
                        aggregateId));
    }
}

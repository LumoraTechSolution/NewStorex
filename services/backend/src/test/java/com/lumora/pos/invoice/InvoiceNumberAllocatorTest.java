package com.lumora.pos.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.sale.SaleRejectedException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * M1-12: a terminal's invoice numbers come from a reserved block, not an unbounded counter.
 *
 * <p>Deliberately not {@code @Transactional} for the same reason as {@code SaleCommitTest}: the
 * behaviour under test is what a real, committed row does across several calls, and a test-managed
 * transaction would mask exactly that.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class InvoiceNumberAllocatorTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    /**
     * The same tenant {@code SaleCommitTest} commits — a desktop database holds exactly one, and
     * this class does not run in its own transaction, so it must reuse the one other real-commit
     * test classes already created rather than adding a second and tripping that invariant for
     * every test in the suite.
     */
    private static final UUID SOLE_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

    @Autowired InvoiceNumberAllocator allocator;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theFirstNumberForANewTerminalIsOne() {
        Fixture fixture = seed();
        String number = allocator.allocate(fixture.tenantId(), fixture.branchId(), fixture.branchCode(), "T1");
        assertThat(number).isEqualTo(fixture.branchCode() + "-T1-000001");
    }

    @Test
    void terminalsOnTheSameBranchDoNotShareASequence() {
        Fixture fixture = seed();
        allocator.allocate(fixture.tenantId(), fixture.branchId(), fixture.branchCode(), "T1");
        allocator.allocate(fixture.tenantId(), fixture.branchId(), fixture.branchCode(), "T1");
        String t2First = allocator.allocate(fixture.tenantId(), fixture.branchId(), fixture.branchCode(), "T2");

        assertThat(t2First).isEqualTo(fixture.branchCode() + "-T2-000001");
    }

    @Test
    void aNewTerminalIsProvisionedWithTheWideDefaultBlock() {
        Fixture fixture = seed();
        allocator.allocate(fixture.tenantId(), fixture.branchId(), fixture.branchCode(), "T1");

        Long rangeStart =
                jdbc.queryForObject(
                        "SELECT range_start FROM invoice_counters WHERE tenant_id = ? AND branch_id = ? AND terminal_code = 'T1'",
                        Long.class,
                        fixture.tenantId(),
                        fixture.branchId());
        Long rangeEnd =
                jdbc.queryForObject(
                        "SELECT range_end FROM invoice_counters WHERE tenant_id = ? AND branch_id = ? AND terminal_code = 'T1'",
                        Long.class,
                        fixture.tenantId(),
                        fixture.branchId());

        assertThat(rangeStart).isEqualTo(1L);
        assertThat(rangeEnd).isEqualTo(999_999L);
    }

    @Test
    void aBlockIsRefusedOnceItsLastNumberIsIssued() {
        Fixture fixture = seed();
        String terminal = "T-" + UNIQUE.incrementAndGet();
        reserveBlock(fixture, terminal, 1, 2);

        String first = allocator.allocate(fixture.tenantId(), fixture.branchId(), fixture.branchCode(), terminal);
        String second = allocator.allocate(fixture.tenantId(), fixture.branchId(), fixture.branchCode(), terminal);

        assertThat(first).isEqualTo(fixture.branchCode() + "-" + terminal + "-000001");
        assertThat(second).isEqualTo(fixture.branchCode() + "-" + terminal + "-000002");

        assertThatThrownBy(
                        () ->
                                allocator.allocate(
                                        fixture.tenantId(), fixture.branchId(), fixture.branchCode(), terminal))
                .isInstanceOf(SaleRejectedException.class)
                .hasMessageContaining("Invoice block exhausted")
                .hasMessageContaining(terminal);
    }

    /**
     * A block a future provisioning step reserved with different bounds (e.g. starting above
     * what a replaced till's predecessor already issued) must never be quietly reset back to the
     * default 1..999999 range on the next ordinary sale.
     */
    @Test
    void anAlreadyProvisionedBlockIsNeverWidenedByAllocate() {
        Fixture fixture = seed();
        String terminal = "T-" + UNIQUE.incrementAndGet();
        reserveBlock(fixture, terminal, 5000, 5010);

        String number = allocator.allocate(fixture.tenantId(), fixture.branchId(), fixture.branchCode(), terminal);
        assertThat(number).isEqualTo(fixture.branchCode() + "-" + terminal + "-005000");

        Long rangeEnd =
                jdbc.queryForObject(
                        "SELECT range_end FROM invoice_counters WHERE tenant_id = ? AND branch_id = ? AND terminal_code = ?",
                        Long.class,
                        fixture.tenantId(),
                        fixture.branchId(),
                        terminal);
        assertThat(rangeEnd).isEqualTo(5010L);
    }

    // ------------------------------------------------------------------- helpers

    private void reserveBlock(Fixture fixture, String terminalCode, long rangeStart, long rangeEnd) {
        jdbc.update(
                """
                INSERT INTO invoice_counters (tenant_id, branch_id, terminal_code, next_seq, range_start, range_end)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                fixture.tenantId(),
                fixture.branchId(),
                terminalCode,
                rangeStart,
                rangeStart,
                rangeEnd);
    }

    private Fixture seed() {
        int n = UNIQUE.incrementAndGet();
        String branchCode = "IB%02d".formatted(n);

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

        return new Fixture(tenantId, branchId, branchCode);
    }

    private record Fixture(long tenantId, long branchId, String branchCode) {}
}

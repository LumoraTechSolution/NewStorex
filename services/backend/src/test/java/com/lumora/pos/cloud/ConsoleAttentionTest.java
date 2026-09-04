package com.lumora.pos.cloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Clearing a cash variance (M6-10).
 *
 * <p>The behaviour worth asserting is the pair: an acknowledged variance leaves the list, and is
 * still findable. Either one alone is a bug. A list that never shrinks is wallpaper; a list that
 * forgets what was dismissed is a way to hide a shortfall from the person it belongs to.
 *
 * <p>This is also the console's only write, so the tenant assertions here are doing a different job
 * from the ones in {@link ConsoleReportTest}: there, a leak shows another shop's takings; here, it
 * would let one shop write a row about another shop's shift.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"cloud", "test"})
@TestPropertySource(
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5444/lumora_test_cloud")
class ConsoleAttentionTest {

    @Autowired ConsoleAttentionService attention;
    @Autowired ConsoleReportService reports;
    @Autowired TenantCredentialService tenants;
    @Autowired ConsoleUserService consoleUsers;
    @Autowired ConsoleSessionService sessions;
    @Autowired JdbcTemplate jdbc;

    /** Console emails are unique across the system and the schema is not reset between tests. */
    private static final java.util.concurrent.atomic.AtomicInteger OWNERS =
            new java.util.concurrent.atomic.AtomicInteger();

    private long mine;
    private long theirs;
    private long mySession;

    @BeforeEach
    void twoShopsAndAnOwner() {
        mine = tenants.provision("Kandy Stores", "Till 1").tenantId();
        theirs = tenants.provision("Galle Stores", "Till 1").tenantId();
        String email = "owner" + OWNERS.incrementAndGet() + "@kandy.lk";
        consoleUsers.create(mine, email, "correct horse battery", "Nimal");
        mySession = sessionIdFor(email, "correct horse battery");
    }

    // ------------------------------------------------------------------ the list, and the button

    @Test
    void anAcknowledgedVarianceLeavesTheList() {
        UUID shift = closedShift(mine, 25_000L);
        assertThat(reports.cashVariances(mine, 14, 10_000L, false)).hasSize(1);

        attention.acknowledge(mine, shift, mySession, "counted it again, my mistake");

        assertThat(reports.cashVariances(mine, 14, 10_000L, false)).isEmpty();
    }

    /** The other half, and the reason the button is safe to press. */
    @Test
    void andIsStillThereUnderReviewed() {
        UUID shift = closedShift(mine, 25_000L);

        attention.acknowledge(mine, shift, mySession, "counted it again, my mistake");

        List<ConsoleReportService.CashVariance> reviewed =
                reports.cashVariances(mine, 14, 10_000L, true);
        assertThat(reviewed).hasSize(1);
        assertThat(reviewed.get(0).shiftClientUuid()).isEqualTo(shift.toString());
        assertThat(reviewed.get(0).varianceMinor()).isEqualTo(25_000L);
        // Named, because "somebody cleared this" is not an answer anybody can act on.
        assertThat(reviewed.get(0).acknowledgedBy()).isEqualTo("Nimal");
        assertThat(reviewed.get(0).acknowledgedAt()).isNotNull();
    }

    @Test
    void acknowledgingOneDoesNotClearTheRest() {
        UUID first = closedShift(mine, 25_000L);
        closedShift(mine, 30_000L);

        attention.acknowledge(mine, first, mySession, null);

        assertThat(reports.cashVariances(mine, 14, 10_000L, false)).hasSize(1);
        assertThat(reports.cashVariances(mine, 14, 10_000L, true)).hasSize(1);
    }

    /**
     * A double-pressed button and a retried request are the same act.
     *
     * <p>And the first one is the one that stands: replacing it would rewrite who reviewed a
     * variance and when, which is the only thing the row is for.
     */
    @Test
    void acknowledgingTwiceKeepsTheFirstAnswer() {
        UUID shift = closedShift(mine, 25_000L);
        var first = attention.acknowledge(mine, shift, mySession, "checked");

        var second = attention.acknowledge(mine, shift, mySession, "checked again");

        assertThat(second.acknowledgedAt()).isEqualTo(first.acknowledgedAt());
        assertThat(second.acknowledgementNote()).isEqualTo("checked");
        assertThat(count("SELECT count(*) FROM shift_acknowledgements WHERE tenant_id = ?", mine))
                .isEqualTo(1);
    }

    @Test
    void aNoteIsOptionalAndBlankIsNotANote() {
        UUID shift = closedShift(mine, 25_000L);

        var acknowledged = attention.acknowledge(mine, shift, mySession, "   ");

        assertThat(acknowledged.acknowledgedAt()).isNotNull();
        assertThat(acknowledged.acknowledgementNote()).isNull();
    }

    // ------------------------------------------------------------------------------- the detail

    @Test
    void theDetailCarriesTheDenominationsBehindTheCount() {
        UUID shift = closedShift(mine, -50_000L);
        countedWith(shift, "CLOSE", 500_00L, 3);
        countedWith(shift, "CLOSE", 100_00L, 12);

        var detail = attention.detail(mine, shift);

        assertThat(detail.varianceMinor()).isEqualTo(-50_000L);
        assertThat(detail.counts()).hasSize(2);
        // Ordered by denomination descending: an owner reads the notes before the coins.
        assertThat(detail.counts().get(0).denominationMinor()).isEqualTo(500_00L);
        assertThat(detail.counts().get(0).qty()).isEqualTo(3);
        assertThat(detail.acknowledgedAt()).isNull();
    }

    @Test
    void aShiftWithNoCountsIsStillReadable() {
        UUID shift = closedShift(mine, 25_000L);

        var detail = attention.detail(mine, shift);

        assertThat(detail.counts()).isEmpty();
        assertThat(detail.branchCode()).isEqualTo("KND");
    }

    // ------------------------------------------------------------------------- the isolation

    /** The assertion this class exists for, given it is the console's only write. */
    @Test
    void oneShopCannotAcknowledgeAnotherShopsVariance() {
        UUID theirShift = closedShift(theirs, 25_000L);

        assertThatThrownBy(() -> attention.acknowledge(mine, theirShift, mySession, "not mine"))
                .hasMessageContaining("No such shift");

        assertThat(count("SELECT count(*) FROM shift_acknowledgements WHERE tenant_id = ?", mine))
                .isZero();
        assertThat(reports.cashVariances(theirs, 14, 10_000L, false)).hasSize(1);
    }

    @Test
    void oneShopCannotEvenReadAnotherShopsVariance() {
        UUID theirShift = closedShift(theirs, 25_000L);

        assertThatThrownBy(() -> attention.detail(mine, theirShift))
                .hasMessageContaining("No such shift");
    }

    /** A uuid that belongs to nobody gets the same answer as one that belongs to somebody else. */
    @Test
    void anInventedShiftIsRefused() {
        assertThatThrownBy(() -> attention.detail(mine, UUID.randomUUID()))
                .hasMessageContaining("No such shift");
    }

    // ------------------------------------------------------------------------------ helpers

    private UUID closedShift(long tenantId, long varianceMinor) {
        UUID clientUuid = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO shifts (
                    client_uuid, tenant_id, branch_code, terminal_code,
                    status, opened_at, opening_float_minor, closed_at, counted_cash_minor,
                    expected_cash_minor, variance_minor)
                VALUES (?, ?, 'KND', 'T1', 'CLOSED', now() - interval '8 hours', 500000,
                        now(), ?, ?, ?)
                """,
                clientUuid,
                tenantId,
                500_000L + varianceMinor,
                500_000L,
                varianceMinor);
        return clientUuid;
    }

    private void countedWith(UUID shiftClientUuid, String phase, long denomination, int qty) {
        jdbc.update(
                """
                INSERT INTO shift_counts (shift_id, phase, denomination_minor, qty)
                SELECT id, ?, ?, ? FROM shifts WHERE client_uuid = ?
                """,
                phase,
                denomination,
                qty,
                shiftClientUuid);
    }

    private long sessionIdFor(String email, String password) {
        sessions.signIn(email, password);
        Long id =
                jdbc.queryForObject(
                        "SELECT id FROM console_sessions ORDER BY id DESC LIMIT 1", Long.class);
        return id == null ? 0L : id;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}

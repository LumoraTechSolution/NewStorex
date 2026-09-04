package com.lumora.pos.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import com.lumora.pos.cloud.ConsoleReportService.OperatorDay;
import java.time.LocalDate;
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
 * Who was on the till, and what they took (M6-13).
 *
 * <p>The join here is the interesting part. A sale carries no operator and never will — the person
 * is a property of the <em>shift</em>, which is what M2 made the unit of accountability — so this
 * attributes takings through {@code shift_client_uuid}. Get that wrong and the numbers are still
 * plausible, still add up to the day's total, and belong to the wrong person.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"cloud", "test"})
@TestPropertySource(
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5444/lumora_test_cloud")
class ConsoleOperatorDayTest {

    private static final String ZONE = "Asia/Colombo";
    private static final LocalDate DAY = LocalDate.of(2026, 8, 30);

    @Autowired ConsoleReportService reports;
    @Autowired TenantCredentialService tenants;
    @Autowired JdbcTemplate jdbc;

    private long mine;
    private long theirs;

    @BeforeEach
    void twoShops() {
        mine = tenants.provision("Kandy Stores", "Till 1").tenantId();
        theirs = tenants.provision("Galle Stores", "Till 1").tenantId();
    }

    @Test
    void takingsAreAttributedToWhoeverOpenedTheShift() {
        UUID nimal = user(mine, "Nimal");
        UUID kamala = user(mine, "Kamala");
        UUID nimalsShift = shift(mine, nimal, "Nimal", 0L);
        UUID kamalasShift = shift(mine, kamala, "Kamala", 0L);
        sale(mine, nimalsShift, 45_000L);
        sale(mine, nimalsShift, 39_000L);
        sale(mine, kamalasShift, 12_000L);

        List<OperatorDay> day = reports.operatorDay(mine, DAY, ZONE);

        assertThat(day).hasSize(2);
        // Ordered by takings: the answer somebody is looking for is at the top.
        assertThat(day.get(0).operator()).isEqualTo("Nimal");
        assertThat(day.get(0).totalMinor()).isEqualTo(84_000L);
        assertThat(day.get(0).saleCount()).isEqualTo(2);
        assertThat(day.get(1).operator()).isEqualTo("Kamala");
        assertThat(day.get(1).totalMinor()).isEqualTo(12_000L);
    }

    /**
     * Somebody who came back after a break is one row with two shifts.
     *
     * <p>"Nimal took Rs. 84,000" is the answer. Two rows because of a lunch break would be
     * arithmetic homework, and the shift count is the visible trace for anybody who cares.
     */
    @Test
    void twoShiftsByOnePersonAreOneRow() {
        UUID nimal = user(mine, "Nimal");
        UUID morning = shift(mine, nimal, "Nimal", 0L);
        UUID afternoon = shift(mine, nimal, "Nimal", 0L);
        sale(mine, morning, 30_000L);
        sale(mine, afternoon, 20_000L);

        List<OperatorDay> day = reports.operatorDay(mine, DAY, ZONE);

        assertThat(day).hasSize(1);
        assertThat(day.get(0).shiftCount()).isEqualTo(2);
        assertThat(day.get(0).totalMinor()).isEqualTo(50_000L);
    }

    /** Variance rides along, summed, because two shifts short by 100 is a pattern. */
    @Test
    void theVarianceIsSummedAcrossThePersonsShifts() {
        UUID nimal = user(mine, "Nimal");
        shift(mine, nimal, "Nimal", -10_000L);
        shift(mine, nimal, "Nimal", -20_000L);

        assertThat(reports.operatorDay(mine, DAY, ZONE).get(0).varianceMinor()).isEqualTo(-30_000L);
    }

    @Test
    void somebodyWhoOpenedAShiftAndSoldNothingIsStillListed() {
        UUID nimal = user(mine, "Nimal");
        shift(mine, nimal, "Nimal", 0L);

        List<OperatorDay> day = reports.operatorDay(mine, DAY, ZONE);

        assertThat(day).hasSize(1);
        assertThat(day.get(0).saleCount()).isZero();
        assertThat(day.get(0).totalMinor()).isZero();
    }

    /**
     * A shift from before M6-13 has no operator, and is listed unnamed rather than dropped.
     *
     * <p>Dropping it would make a day's takings on this screen quietly disagree with the day's
     * takings on the next one, which is a worse failure than an unnamed row.
     */
    @Test
    void aShiftFromBeforeThisFeatureIsListedWithoutAName() {
        UUID nameless = shift(mine, null, null, 0L);
        sale(mine, nameless, 15_000L);

        List<OperatorDay> day = reports.operatorDay(mine, DAY, ZONE);

        assertThat(day).hasSize(1);
        assertThat(day.get(0).operator()).isNull();
        assertThat(day.get(0).totalMinor()).isEqualTo(15_000L);
    }

    /**
     * The name comes from the synced user when it has arrived, and from the shift's snapshot when
     * it has not.
     *
     * <p>Shifts and users drain through the same outbox with no ordering between them, so a shift
     * can land minutes before the person who opened it. A blank name until some later batch happened
     * to drain would look broken rather than pending.
     */
    @Test
    void theSnapshotCarriesTheNameUntilTheUserArrives() {
        UUID notYetSynced = UUID.randomUUID();
        UUID theirShift = shift(mine, notYetSynced, "Nimal", 0L);
        sale(mine, theirShift, 10_000L);

        assertThat(reports.operatorDay(mine, DAY, ZONE).get(0).operator()).isEqualTo("Nimal");
    }

    /** And once the user arrives, their current name wins over the snapshot. */
    @Test
    void theSyncedUserOverridesTheSnapshotAfterARename() {
        UUID person = user(mine, "Nimal Perera");
        shift(mine, person, "Nimal", 0L);

        assertThat(reports.operatorDay(mine, DAY, ZONE).get(0).operator()).isEqualTo("Nimal Perera");
    }

    /**
     * The flag the console's first line is built from (M6-14).
     *
     * <p>A shift that has not been delivered closed is a shift somebody is standing at. Nothing
     * stores "on now" — inventing a column for it would be a second thing to keep in step with the
     * status the till already sends.
     */
    @Test
    void aPersonWhoseShiftIsStillOpenReadsAsOnNow() {
        UUID nimal = user(mine, "Nimal");
        UUID openShift = shift(mine, nimal, "Nimal", 0L);
        jdbc.update(
                "UPDATE shifts SET status = 'OPEN', closed_at = NULL, variance_minor = NULL WHERE client_uuid = ?",
                openShift);

        OperatorDay day = reports.operatorDay(mine, DAY, ZONE).get(0);

        assertThat(day.onNow()).isTrue();
        assertThat(day.openedAt()).isNotNull();
    }

    @Test
    void aPersonWhoseShiftsAreAllClosedHasGoneHome() {
        UUID nimal = user(mine, "Nimal");
        shift(mine, nimal, "Nimal", 0L);

        assertThat(reports.operatorDay(mine, DAY, ZONE).get(0).onNow()).isFalse();
    }

    @Test
    void yesterdaysShiftIsNotOnTodaysList() {
        UUID nimal = user(mine, "Nimal");
        UUID yesterday = shiftOn(mine, nimal, "Nimal", 0L, DAY.minusDays(1));
        sale(mine, yesterday, 99_000L);

        assertThat(reports.operatorDay(mine, DAY, ZONE)).isEmpty();
        assertThat(reports.operatorDay(mine, DAY.minusDays(1), ZONE)).hasSize(1);
    }

    /** The assertion every query in this class shares with the rest of the console. */
    @Test
    void oneShopsStaffNeverAppearInAnotherOwnersDay() {
        UUID theirNimal = user(theirs, "Nimal");
        UUID theirShift = shift(theirs, theirNimal, "Nimal", 0L);
        sale(theirs, theirShift, 45_000L);

        assertThat(reports.operatorDay(mine, DAY, ZONE)).isEmpty();
        assertThat(reports.operatorDay(theirs, DAY, ZONE)).hasSize(1);
    }

    // -------------------------------------------------------------------------------- helpers

    private UUID user(long tenantId, String displayName) {
        UUID clientUuid = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO users (client_uuid, tenant_id, code, display_name, role, active)
                VALUES (?, ?, ?, ?, 'CASHIER', true)
                """,
                clientUuid,
                tenantId,
                clientUuid.toString().substring(0, 8),
                displayName);
        return clientUuid;
    }

    private UUID shift(long tenantId, UUID operator, String operatorName, long varianceMinor) {
        return shiftOn(tenantId, operator, operatorName, varianceMinor, DAY);
    }

    private UUID shiftOn(
            long tenantId, UUID operator, String operatorName, long varianceMinor, LocalDate day) {
        UUID clientUuid = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO shifts (
                    client_uuid, tenant_id, branch_code, terminal_code, status,
                    opened_at, opening_float_minor, closed_at, counted_cash_minor,
                    expected_cash_minor, variance_minor,
                    opened_by_client_uuid, opened_by_name)
                VALUES (?, ?, 'KND', 'T1', 'CLOSED',
                        (?::date + time '09:00') AT TIME ZONE ?, 500000,
                        (?::date + time '18:00') AT TIME ZONE ?, ?, 500000, ?, ?, ?)
                """,
                clientUuid,
                tenantId,
                day.toString(),
                ZONE,
                day.toString(),
                ZONE,
                500_000L + varianceMinor,
                varianceMinor,
                operator,
                operatorName);
        return clientUuid;
    }

    private void sale(long tenantId, UUID shiftClientUuid, long totalMinor) {
        jdbc.update(
                """
                INSERT INTO sales (
                    client_uuid, tenant_id, branch_code, terminal_code, invoice_number,
                    sold_at, subtotal_minor, discount_minor, tax_minor, total_minor,
                    tax_mode, tax_rate_bp, shift_client_uuid)
                VALUES (?, ?, 'KND', 'T1', ?, (?::date + time '12:00') AT TIME ZONE ?,
                        ?, 0, 0, ?, 'INCLUSIVE', 1800, ?)
                """,
                UUID.randomUUID(),
                tenantId,
                "KND-T1-" + UUID.randomUUID().toString().substring(0, 8),
                DAY.toString(),
                ZONE,
                totalMinor,
                totalMinor,
                shiftClientUuid);
    }
}

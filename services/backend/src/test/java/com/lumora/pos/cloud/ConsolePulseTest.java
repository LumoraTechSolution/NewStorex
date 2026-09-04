package com.lumora.pos.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import com.lumora.pos.cloud.ConsoleReportService.PulseSlot;
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
 * The shape of a trading day (M6-14).
 *
 * <p>Two things have to be right or the graphic lies rather than fails. The slots must be the
 * shop's own clock — a sale at 9am in Kandy belongs in the 9am slot, not in the 3:30am UTC one it
 * is stored at. And the baseline must be the same weekday: a grocery's Saturday and its Tuesday are
 * different businesses, and comparing today to a fortnight's blur would tell an owner every
 * Saturday was exceptional.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"cloud", "test"})
@TestPropertySource(
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5444/lumora_test_cloud")
class ConsolePulseTest {

    private static final String ZONE = "Asia/Colombo";
    /** A Monday. The baseline looks four weeks back at the same weekday, so this matters. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);

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
    void everyQuarterHourOfTheDayIsPresentEvenWhenNothingSold() {
        List<PulseSlot> pulse = reports.pulse(mine, MONDAY, ZONE);

        // Ninety-six slots, always. A graphic that only received the busy ones would have to guess
        // where the gaps were, and would draw a shop that never closed.
        assertThat(pulse).hasSize(96);
        assertThat(pulse.get(0).minuteOfDay()).isZero();
        assertThat(pulse.get(95).minuteOfDay()).isEqualTo(23 * 60 + 45);
        assertThat(pulse).allMatch(slot -> slot.saleCount() == 0);
    }

    /** The assertion the whole graphic rests on: slots are the shop's clock, not UTC. */
    @Test
    void aSaleLandsInTheSlotTheShopSoldItIn() {
        sale(mine, MONDAY, 9, 20, 45_000L);

        assertThat(slotAt(reports.pulse(mine, MONDAY, ZONE), 9 * 60 + 15).saleCount()).isEqualTo(1);
    }

    @Test
    void salesInOneQuarterHourAreCountedAndTotalled() {
        sale(mine, MONDAY, 10, 0, 45_000L);
        sale(mine, MONDAY, 10, 7, 30_000L);
        sale(mine, MONDAY, 10, 14, 25_000L);
        // The next slot along, so the boundary is real.
        sale(mine, MONDAY, 10, 15, 99_000L);

        List<PulseSlot> pulse = reports.pulse(mine, MONDAY, ZONE);

        assertThat(slotAt(pulse, 10 * 60).saleCount()).isEqualTo(3);
        assertThat(slotAt(pulse, 10 * 60).totalMinor()).isEqualTo(100_000L);
        assertThat(slotAt(pulse, 10 * 60 + 15).saleCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------ the baseline

    @Test
    void theBaselineAveragesTheSameWeekdayBehindIt() {
        // Three Mondays: two, four and six sales in the ten o'clock slot.
        sale(mine, MONDAY.minusWeeks(1), 10, 0, 10_000L);
        sale(mine, MONDAY.minusWeeks(1), 10, 5, 10_000L);
        for (int i = 0; i < 4; i++) sale(mine, MONDAY.minusWeeks(2), 10, i, 10_000L);
        for (int i = 0; i < 6; i++) sale(mine, MONDAY.minusWeeks(3), 10, i, 10_000L);

        assertThat(slotAt(reports.pulse(mine, MONDAY, ZONE), 10 * 60).usualSaleCount())
                .isEqualTo(4.0);
    }

    /**
     * The baseline carries money as well as a count, and the console needs both.
     *
     * <p>The sentence above the graphic is in rupees — "ahead of a normal Monday by this hour" —
     * and the only honest source for it is the same array the graphic draws. Computing it instead
     * from a daily total scaled by the time of day would invent an intraday shape nobody measured.
     */
    @Test
    void theBaselineAveragesWhatTheShopTookAsWellAsHowOften() {
        sale(mine, MONDAY.minusWeeks(1), 10, 0, 20_000L);
        sale(mine, MONDAY.minusWeeks(1), 10, 5, 10_000L);
        sale(mine, MONDAY.minusWeeks(2), 10, 0, 10_000L);

        PulseSlot slot = slotAt(reports.pulse(mine, MONDAY, ZONE), 10 * 60);

        // Three sales over two trading Mondays: one and a half sales, Rs 200 a Monday.
        assertThat(slot.usualSaleCount()).isEqualTo(1.5);
        assertThat(slot.usualTotalMinor()).isEqualTo(20_000.0);
    }

    /**
     * A shop closed on some of those Mondays is compared against the ones it opened.
     *
     * <p>Averaging over four regardless would halve the baseline of a shop that takes a week off,
     * and its first day back would look like a record.
     */
    @Test
    void aWeekTheShopWasShutDoesNotDragTheBaselineDown() {
        sale(mine, MONDAY.minusWeeks(1), 10, 0, 10_000L);
        sale(mine, MONDAY.minusWeeks(1), 10, 5, 10_000L);
        // Nothing at all on the other three Mondays.

        assertThat(slotAt(reports.pulse(mine, MONDAY, ZONE), 10 * 60).usualSaleCount())
                .isEqualTo(2.0);
    }

    @Test
    void aTuesdayIsNotPartOfAMondaysBaseline() {
        for (int i = 0; i < 8; i++) sale(mine, MONDAY.minusDays(6), 10, i, 10_000L);

        assertThat(slotAt(reports.pulse(mine, MONDAY, ZONE), 10 * 60).usualSaleCount()).isZero();
    }

    @Test
    void todayIsNotPartOfItsOwnBaseline() {
        for (int i = 0; i < 5; i++) sale(mine, MONDAY, 10, i, 10_000L);

        PulseSlot slot = slotAt(reports.pulse(mine, MONDAY, ZONE), 10 * 60);

        assertThat(slot.saleCount()).isEqualTo(5);
        assertThat(slot.usualSaleCount()).isZero();
    }

    @Test
    void aMondayFiveWeeksBackIsTooOldToCount() {
        for (int i = 0; i < 9; i++) sale(mine, MONDAY.minusWeeks(5), 10, i, 10_000L);

        assertThat(slotAt(reports.pulse(mine, MONDAY, ZONE), 10 * 60).usualSaleCount()).isZero();
    }

    // ----------------------------------------------------------------------------- the isolation

    @Test
    void oneShopsDayIsNeverAnotherShopsShape() {
        sale(theirs, MONDAY, 10, 0, 45_000L);
        sale(theirs, MONDAY.minusWeeks(1), 10, 0, 45_000L);

        List<PulseSlot> pulse = reports.pulse(mine, MONDAY, ZONE);

        assertThat(slotAt(pulse, 10 * 60).saleCount()).isZero();
        assertThat(slotAt(pulse, 10 * 60).usualSaleCount()).isZero();
        assertThat(slotAt(reports.pulse(theirs, MONDAY, ZONE), 10 * 60).saleCount()).isEqualTo(1);
    }

    // --------------------------------------------------------------------------------- helpers

    private static PulseSlot slotAt(List<PulseSlot> pulse, int minuteOfDay) {
        return pulse.stream()
                .filter(slot -> slot.minuteOfDay() == minuteOfDay)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no slot at minute " + minuteOfDay));
    }

    private void sale(long tenantId, LocalDate day, int hour, int minute, long totalMinor) {
        jdbc.update(
                """
                INSERT INTO sales (
                    client_uuid, tenant_id, branch_code, terminal_code, invoice_number,
                    sold_at, subtotal_minor, discount_minor, tax_minor, total_minor,
                    tax_mode, tax_rate_bp)
                VALUES (?, ?, 'KND', 'T1', ?,
                        (?::date + make_time(?, ?, 0)) AT TIME ZONE ?,
                        ?, 0, 0, ?, 'INCLUSIVE', 1800)
                """,
                UUID.randomUUID(),
                tenantId,
                "KND-T1-" + UUID.randomUUID().toString().substring(0, 8),
                day.toString(),
                hour,
                minute,
                ZONE,
                totalMinor,
                totalMinor);
    }
}

package com.lumora.pos.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lumora.pos.sync.Entitlement;
import com.lumora.pos.testfixtures.ShopFixture;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The till's side of the downward pull (M4-09).
 *
 * <p>Every test here is about a way this could turn into the thing §A forbids: a shop that stops
 * working because the cloud is unreachable, out of date, or cross. The happy path — a licensed
 * answer caching a flag set — is one test; the other four are the failure modes, because those are
 * what a licensing mechanism gets wrong and they are invisible until a real shop hits them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class EntitlementStoreTest {

    @Autowired EntitlementStore store;
    @Autowired ShopFixture fixture;
    @Autowired JdbcTemplate jdbc;

    private long tenantId;

    @BeforeEach
    void aShopWithNothingCachedYet() {
        tenantId = fixture.seed().tenantId();
        jdbc.update("DELETE FROM entitlement_flags WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM entitlements WHERE tenant_id = ?", tenantId);
    }

    /**
     * Rule 1, and the one that matters on the morning a shop opens for the first time. A till the
     * cloud has never answered must be fully capable, not fully locked.
     */
    @Test
    void aTillTheCloudHasNeverAnsweredAllowsEverything() {
        assertThat(store.cached(tenantId)).isEmpty();

        assertThat(store.allows(tenantId, "back_office")).isTrue();
        assertThat(store.allows(tenantId, "stocktake")).isTrue();
        // Including a capability nothing has ever heard of: absence of an answer is not evidence.
        assertThat(store.allows(tenantId, "a_flag_invented_in_2029")).isTrue();
    }

    @Test
    void aLicensedAnswerIsCachedAndItsFlagsGovern() {
        store.record(tenantId, licensed("standard", List.of("back_office", "customers")));

        assertThat(store.allows(tenantId, "back_office")).isTrue();
        assertThat(store.allows(tenantId, "customers")).isTrue();
        // Now that there *is* an answer, a name outside it is a no. This is the only way `allows`
        // ever returns false, and it takes a cloud that said so.
        assertThat(store.allows(tenantId, "stocktake")).isFalse();

        EntitlementStore.Cached cached = store.cached(tenantId).orElseThrow();
        assertThat(cached.licensed()).isTrue();
        assertThat(cached.planCode()).isEqualTo("standard");
        assertThat(cached.flags()).containsExactly("back_office", "customers");
    }

    /**
     * Rule 2, and the argument V209 made about the console applied to the shop PC: a shop that is
     * late paying loses its sync, not its catalogue.
     */
    @Test
    void aLapseRecordsTheLapseAndWithdrawsNothing() {
        store.record(tenantId, licensed("standard", List.of("back_office", "customers")));

        Instant expiredYesterday = Instant.now().minusSeconds(86_400);
        store.record(
                tenantId,
                new Entitlement(
                        false,
                        "standard",
                        "Standard",
                        expiredYesterday.minusSeconds(2_592_000),
                        expiredYesterday,
                        1,
                        5,
                        // The cloud sends nothing, because it resolves flags from a covering
                        // licence and there is none. Writing that through is the bug this guards.
                        List.of(),
                        Instant.now()));

        EntitlementStore.Cached cached = store.cached(tenantId).orElseThrow();
        assertThat(cached.licensed()).isFalse();
        assertThat(cached.licenceExpiresAt()).isCloseTo(expiredYesterday, within1s());

        // The whole point. The owner can still open the back office and add a product while they
        // sort the payment out.
        assertThat(cached.flags()).containsExactly("back_office", "customers");
        assertThat(store.allows(tenantId, "back_office")).isTrue();

        // And the last time it was genuinely fine is still on the row, so a screen can say when.
        assertThat(cached.licensedAt()).isNotNull();
    }

    /**
     * A capability the plan drops has to actually go. The set is replaced rather than merged —
     * merging would make a downgrade impossible to express, which is the mirror image of the
     * previous test and just as wrong.
     */
    @Test
    void aLicensedAnswerReplacesTheFlagSetRatherThanAddingToIt() {
        store.record(tenantId, licensed("plus", List.of("back_office", "stocktake", "customers")));
        store.record(tenantId, licensed("standard", List.of("back_office", "customers")));

        assertThat(store.cached(tenantId).orElseThrow().flags())
                .containsExactly("back_office", "customers");
        assertThat(store.allows(tenantId, "stocktake")).isFalse();
    }

    /** Nothing here reads a clock to decide whether the cache still counts — see rule 3. */
    @Test
    void anAnswerFromLongAgoStillGoverns() {
        store.record(tenantId, licensed("standard", List.of("back_office")));
        jdbc.update(
                "UPDATE entitlements SET checked_at = now() - interval '60 days' WHERE tenant_id = ?",
                tenantId);

        assertThat(store.allows(tenantId, "back_office")).isTrue();
        // Old, and legibly old — which is what checked_at is for, rather than for expiring it.
        assertThat(store.cached(tenantId).orElseThrow().checkedAt())
                .isBefore(Instant.now().minusSeconds(86_400));
    }

    private static Entitlement licensed(String planCode, List<String> flags) {
        Instant now = Instant.now();
        return new Entitlement(
                true,
                planCode,
                planCode.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + planCode.substring(1),
                now.minusSeconds(60),
                now.plusSeconds(2_592_000),
                1,
                5,
                flags,
                now);
    }

    private static org.assertj.core.data.TemporalUnitOffset within1s() {
        return new org.assertj.core.data.TemporalUnitWithinOffset(
                1, java.time.temporal.ChronoUnit.SECONDS);
    }
}

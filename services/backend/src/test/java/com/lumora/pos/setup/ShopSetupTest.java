package com.lumora.pos.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.invoice.InvoiceNumberAllocator;
import com.lumora.pos.user.Role;
import com.lumora.pos.user.UserService;
import com.lumora.pos.web.RejectedException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The first-run wizard (M5-03).
 *
 * <h2>This is the only test here that needs an empty database</h2>
 *
 * Everything else in this suite builds on {@link com.lumora.pos.testfixtures.ShopFixture}, which
 * seeds one shared tenant because {@code LocalShop} asserts a desktop database holds exactly one.
 * This class tests the state <em>before</em> that: a migrated schema with nothing in it, which is
 * what a shopkeeper's PC looks like the moment the installer finishes.
 *
 * <h2>How it gets one without wrecking the run</h2>
 *
 * The whole class runs inside a transaction that {@link Transactional} rolls back after each test,
 * so it can empty the database in {@link #emptyTheTill}, build a shop, assert against it, and
 * leave the shared fixture the rest of the suite depends on untouched — none of it ever commits.
 *
 * <p>Getting there cost two wrong answers, both recorded on {@link #SHOP_TABLES} because both are
 * the kind of thing that gets re-tried by the next person: deleting a hand-written subset of
 * tables (a foreign key violation), and then {@code TRUNCATE ... CASCADE} (a deadlock against
 * {@code PinAttemptGuard}'s {@code REQUIRES_NEW} connection, which hangs rather than fails).
 *
 * <p>The one test that must <em>not</em> run in that transaction is
 * {@link #aFailedSetupLeavesNoHalfBuiltShop}, which asserts a rollback and would pass trivially
 * inside one. It suspends it, and says so.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
@Transactional
class ShopSetupTest {

    @Autowired ShopSetupService setup;
    @Autowired UserService users;
    @Autowired JdbcTemplate jdbc;

    /**
     * Empties the shop inside this test's own transaction, so the wizard sees what an installer
     * leaves behind.
     *
     * <h2>Deletes, and specifically not {@code TRUNCATE ... CASCADE}</h2>
     *
     * {@code TRUNCATE} is the obvious way to say "everything that hangs off a tenant goes", it is
     * transactional in Postgres, and it <b>hung the suite</b>. It takes an {@code ACCESS
     * EXCLUSIVE} lock on every table it touches, and {@link com.lumora.pos.user.PinAttemptGuard}
     * is annotated {@code REQUIRES_NEW} — so authenticating the owner opens a <em>second</em>
     * connection, which blocks on a lock the first connection holds until it commits, which it
     * cannot do while waiting for the second. A deadlock no timeout catches, in a suite that had
     * been green a minute earlier.
     *
     * <p>{@code DELETE} takes row locks instead, which the second connection can wait through, and
     * the same transaction rolls it all back at the end of the test. The tables are listed
     * children-first, and the cost of that is exactly what it looks like: this list has to grow
     * when a migration adds a table that references one of these. That is the honest trade against
     * a lock that deadlocks, and the failure mode is loud rather than a hang.
     *
     * <p>Rolled back with the test, so the shared fixture the rest of the suite depends on never
     * sees any of it.
     */
    private static final List<String> SHOP_TABLES =
            List.of(
                    // Leaves: rows that reference something and are referenced by nothing.
                    "outbox",
                    "pin_attempts",
                    "entitlement_flags",
                    "entitlements",
                    "sessions",
                    "refund_payments",
                    "refund_items",
                    "refunds",
                    "tax_invoices",
                    "sale_payments",
                    "sale_items",
                    "sales",
                    "stocktake_items",
                    "stocktakes",
                    "goods_receipt_items",
                    "goods_receipts",
                    "stock_movements",
                    "cash_movements",
                    "shift_counts",
                    "shifts",
                    "invoice_counters",
                    "customers",
                    "suppliers",
                    "product_barcodes",
                    "products",
                    "product_categories",
                    // Then the shop itself, innermost last.
                    "users",
                    "tenant_settings",
                    "branches",
                    "tenants");

    @BeforeEach
    void emptyTheTill() {
        // Only inside the class-level transaction, which is every test but the rollback one. That
        // test suspends the transaction on purpose, and deleting outside one would commit — taking
        // the shared fixture the rest of the suite depends on with it. It does not need an empty
        // database anyway: it asserts on the specific rows it tried to create.
        if (!TestTransaction.isActive()) {
            return;
        }
        for (String table : SHOP_TABLES) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private static ShopSetupRequest request() {
        return new ShopSetupRequest(
                "Galle Traders",
                "gll",
                "Galle Main",
                "t2",
                "42 Lighthouse Street, Galle",
                null,
                null,
                null,
                "owner",
                "Nimal Perera",
                "9134");
    }

    // ----------------------------------------------------------------- the state it exists for

    /**
     * A migrated database with nothing in it is exactly what the installer leaves behind, and the
     * wizard has to be able to tell.
     */
    @Test
    void aFreshlyInstalledTillIsNotProvisioned() {
        assertThat(setup.isProvisioned()).isFalse();
    }

    /**
     * One call produces a shop somebody can sign in to and sell from.
     *
     * <p>Asserted as a whole rather than field by field, because the thing being tested is that
     * all five parts arrive together — a tenant with no branch or a branch with no owner is the
     * failure this is a single transaction to prevent.
     */
    @Test
    void provisioningCreatesAShopABranchSettingsAnOwnerAndABlock() {
        ShopSetupService.Provisioned provisioned = setup.provision(request());

        assertThat(provisioned.shopName()).isEqualTo("Galle Traders");
        assertThat(setup.isProvisioned()).isTrue();

        assertThat(count("SELECT count(*) FROM tenants")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM branches WHERE tenant_id = ?", provisioned.tenantId()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tenant_settings WHERE tenant_id = ?", provisioned.tenantId()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM users WHERE tenant_id = ?", provisioned.tenantId()))
                .isEqualTo(1);
    }

    /** The owner must be able to sign in immediately, or the till is set up and unusable. */
    @Test
    void theOwnerCanSignInWithThePinTheyChose() {
        ShopSetupService.Provisioned provisioned = setup.provision(request());

        UserService.Operator operator =
                users.authenticate(provisioned.tenantId(), provisioned.ownerCode(), "9134");

        assertThat(operator.role()).isEqualTo(Role.OWNER);
        assertThat(operator.displayName()).isEqualTo("Nimal Perera");
    }

    // ----------------------------------------------------------------- the codes on the invoice

    /**
     * Codes are upper-cased, because they end up inside a document number and
     * {@code KND-T1-000001} is not the same string as {@code knd-t1-000001}.
     */
    @Test
    void codesAreUpperCasedBecauseTheyFormPartOfAnInvoiceNumber() {
        ShopSetupService.Provisioned provisioned = setup.provision(request());

        assertThat(provisioned.branchCode()).isEqualTo("GLL");
        assertThat(provisioned.terminalCode()).isEqualTo("T2");
        assertThat(setup.identity().terminalCode()).isEqualTo("T2");
    }

    /** A space in a code is a typo, and repairing it silently produces a number nobody chose. */
    @Test
    void aCodeWithASpaceIsRefusedRatherThanRepaired() {
        ShopSetupRequest spaced =
                new ShopSetupRequest(
                        "Galle Traders", "G LL", "Galle Main", "T2", null, null, null, null,
                        "OWNER", "Nimal Perera", "9134");

        assertThatThrownBy(() -> setup.provision(spaced))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("space");
        assertThat(setup.isProvisioned()).isFalse();
    }

    /**
     * Both document types get a block at setup, so a shop's first refund is not the moment its
     * credit-note counter appears. V108: a credit note must never take a number from the invoice
     * sequence.
     */
    @Test
    void everyDocumentTypeGetsItsBlockBeforeTheFirstSale() {
        ShopSetupService.Provisioned provisioned = setup.provision(request());

        assertThat(count("SELECT count(*) FROM invoice_counters WHERE tenant_id = ?", provisioned.tenantId()))
                .isEqualTo(InvoiceNumberAllocator.DocType.values().length);
    }

    /** The block starts where a shop that has issued nothing should start. */
    @Test
    void theFirstInvoiceNumberIsOne() {
        ShopSetupService.Provisioned provisioned = setup.provision(request());

        Long nextSeq =
                jdbc.queryForObject(
                        """
                        SELECT next_seq FROM invoice_counters
                         WHERE tenant_id = ? AND doc_type = 'INVOICE'
                        """,
                        Long.class,
                        provisioned.tenantId());

        assertThat(nextSeq).isEqualTo(1L);
    }

    // ----------------------------------------------------------------- the guard

    /**
     * The endpoint is unauthenticated, so this refusal is the only thing standing between a
     * provisioned till and a second identity — which would give it a second invoice sequence.
     */
    @Test
    void aTillThatAlreadyHasAShopRefusesToBeSetUpAgain() {
        setup.provision(request());

        assertThatThrownBy(() -> setup.provision(request()))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("already belongs to a shop");

        assertThat(count("SELECT count(*) FROM tenants")).isEqualTo(1);
    }

    /**
     * A rejected setup leaves nothing behind, so the next attempt starts from a clean database
     * rather than from half a shop.
     *
     * <p>The PIN is what fails here, and the choice is deliberate: it is validated by
     * {@code UserService} <em>after</em> the tenant, branch and settings rows have already been
     * written, so a service that was not one transaction would leave all three behind. A test that
     * failed on the shop name instead would pass whether or not the rollback worked.
     *
     * <p><b>This test runs without the class-level transaction</b>, which is the only way the
     * assertion means anything: inside one, the rows would be invisible at the end regardless of
     * what {@code provision} did, and the test would pass against a service with no transaction at
     * all. {@code propagation = NOT_SUPPORTED} suspends it, so the writes and their rollback are
     * real. The cleanup in {@link #emptyTheTill} runs before this and commits for the same reason —
     * which is safe because a failed setup is the one case that leaves nothing to clean up.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aFailedSetupLeavesNoHalfBuiltShop() {
        ShopSetupRequest badPin =
                new ShopSetupRequest(
                        "Galle Traders", "GLL", "Galle Main", "T2", null, null, null, null,
                        "OWNER", "Nimal Perera", "1");

        assertThatThrownBy(() -> setup.provision(badPin)).isInstanceOf(RejectedException.class);

        assertThat(count("SELECT count(*) FROM tenants WHERE name = 'Galle Traders'")).isZero();
        assertThat(count("SELECT count(*) FROM branches WHERE code = 'GLL'")).isZero();
    }

    // ----------------------------------------------------------------- what the receipt prints

    /** The five values that replaced five hardcoded constants in {@code page.tsx}. */
    @Test
    void identityIsWhatTheReceiptHeaderNeeds() {
        setup.provision(request());

        ShopSetupService.ShopIdentity identity = setup.identity();

        assertThat(identity.shopName()).isEqualTo("Galle Traders");
        assertThat(identity.shopAddress()).isEqualTo("42 Lighthouse Street, Galle");
        assertThat(identity.branchCode()).isEqualTo("GLL");
        assertThat(identity.branchName()).isEqualTo("Galle Main");
        assertThat(identity.terminalCode()).isEqualTo("T2");
    }

    /**
     * A shop that is not VAT registered has no TIN, and that is a finished setup rather than an
     * unfinished one — {@code TaxInvoiceService} already refuses to issue against a missing
     * identity rather than printing a guess.
     */
    @Test
    void aShopWithNoVatNumberIsStillAFinishedSetup() {
        setup.provision(request());

        assertThat(setup.isProvisioned()).isTrue();
        assertThat(setup.identity().shopName()).isEqualTo("Galle Traders");
    }

    /** An address is optional; a receipt without one is untidy rather than broken. */
    @Test
    void theAddressIsOptional() {
        ShopSetupRequest noAddress =
                new ShopSetupRequest(
                        "Galle Traders", "GLL", "Galle Main", "T2", "   ", null, null, null,
                        "OWNER", "Nimal Perera", "9134");

        setup.provision(noAddress);

        assertThat(setup.identity().shopAddress()).isNull();
    }

    private int count(String sql, Object... args) {
        Integer found = jdbc.queryForObject(sql, Integer.class, args);
        return found == null ? 0 : found;
    }
}

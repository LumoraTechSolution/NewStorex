package com.lumora.pos.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.product.ProductAdminService.ProductDraft;
import com.lumora.pos.testfixtures.ShopFixture;
import com.lumora.pos.testfixtures.ShopFixture.Shop;
import com.lumora.pos.web.RejectedException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Editing the catalogue (M3-02).
 *
 * <p>Two things are worth more than the CRUD here, and most of this file is about them. A barcode
 * must resolve to exactly one product, because that is the entire reason a scan is a zero-click
 * action (M1-08) — so the tests that matter are the ones that try to break it. And an edit must
 * reach the till: a price changed in the back office that {@link ProductLookup} keeps serving stale
 * is the failure a shop discovers at the counter, in front of a customer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
// Rolled back after each test. Nothing here needs a commit to be true, and a catalogue test that
// leaves thirty products behind changes what every later test sees in a shared tenant — which is
// exactly how ProductLookupTest started failing when this file was added.
@Transactional
class ProductAdminTest {

    @Autowired ProductAdminService admin;
    @Autowired ProductLookup lookup;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    private static ProductDraft draft(String sku, String name, long priceMinor, String... barcodes) {
        return new ProductDraft(
                UUID.randomUUID(),
                sku,
                name,
                priceMinor,
                "INCLUSIVE",
                1800,
                null,
                List.of(barcodes),
                null);
    }

    /** A unique suffix, so these tests can run in any order against one shared tenant. */
    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // -------------------------------------------------------------------- the happy path

    @Test
    void aProductCreatedInTheBackOfficeIsImmediatelyScannableAtTheTill() {
        Shop shop = fixtures.seed();
        String barcode = unique("BC");

        ProductRow created =
                admin.create(shop.tenantId(), draft(unique("SKU"), "Cinnamon 100g", 78_500, barcode));

        assertThat(created.priceMinor()).isEqualTo(78_500);
        assertThat(created.barcodes()).containsExactly(barcode);
        assertThat(created.active()).isTrue();

        // The point of the test: the till's own lookup, not a re-read of the admin view.
        assertThat(lookup.byBarcode(barcode))
                .hasValueSatisfying(
                        summary -> {
                            assertThat(summary.name()).isEqualTo("Cinnamon 100g");
                            assertThat(summary.priceMinor()).isEqualTo(78_500);
                        });
    }

    /**
     * A price change reaches the till.
     *
     * <p>Trivial to assert and the single most consequential thing this screen does. Everything
     * else on it can be corrected tomorrow; a stale price is discovered at the counter with a
     * customer watching.
     */
    @Test
    void editingAPriceChangesWhatTheTillCharges() {
        Shop shop = fixtures.seed();
        String barcode = unique("BC");
        ProductRow product = admin.create(shop.tenantId(), draft(unique("SKU"), "Salt 1kg", 12_000, barcode));

        admin.save(
                shop.tenantId(),
                product.id(),
                new ProductDraft(
                        null,
                        product.sku(),
                        "Salt 1kg",
                        13_500,
                        "INCLUSIVE",
                        1800,
                        null,
                        List.of(barcode),
                        null));

        assertThat(lookup.byBarcode(barcode)).hasValueSatisfying(s -> assertThat(s.priceMinor()).isEqualTo(13_500));
    }

    // ------------------------------------------------------------------------- barcodes

    /**
     * The rule V103 exists for. A code that two products claim is a scan that has to stop and ask,
     * which is the one thing the scan path is not allowed to do.
     */
    @Test
    void aBarcodeCannotBeTakenFromAnotherProduct() {
        Shop shop = fixtures.seed();
        String barcode = unique("BC");
        admin.create(shop.tenantId(), draft(unique("SKU"), "Tinned Fish", 45_000, barcode));

        assertThatThrownBy(
                        () ->
                                admin.create(
                                        shop.tenantId(),
                                        draft(unique("SKU"), "Tinned Fish (case)", 540_000, barcode)))
                .isInstanceOf(RejectedException.class)
                // Names the product holding it, because "unique constraint violated" tells the
                // person holding the packet nothing they can act on.
                .hasMessageContaining("already on Tinned Fish");
    }

    /**
     * The list replaces what is stored, and the first entry becomes the primary.
     *
     * <p>The second code is the case a single {@code products.barcode} column could not express:
     * the same goods reaching the shop under two supplier codes.
     */
    @Test
    void savingReplacesTheWholeBarcodeListAndTheFirstIsPrimary() {
        Shop shop = fixtures.seed();
        String first = unique("BC");
        String second = unique("BC");
        String third = unique("BC");

        ProductRow product =
                admin.create(shop.tenantId(), draft(unique("SKU"), "Milk Powder 400g", 139_000, first, second));
        assertThat(product.barcodes()).containsExactly(first, second);

        // Drop the first, keep the second, add a third — and the second is now the primary.
        ProductRow saved =
                admin.save(
                        shop.tenantId(),
                        product.id(),
                        new ProductDraft(
                                null,
                                product.sku(),
                                product.name(),
                                product.priceMinor(),
                                "INCLUSIVE",
                                1800,
                                null,
                                List.of(second, third),
                                null));

        assertThat(saved.barcodes()).containsExactly(second, third);
        assertThat(lookup.byBarcode(first)).isEmpty();
        assertThat(lookup.byBarcode(third)).isPresent();

        String primary =
                jdbc.queryForObject(
                        "SELECT barcode FROM product_barcodes WHERE product_id = ? AND is_primary",
                        String.class,
                        product.id());
        assertThat(primary).isEqualTo(second);
    }

    /**
     * Re-saving a product without touching its codes must not churn their identity.
     *
     * <p>M3-12 syncs {@code product_barcodes}; deleting and reinserting on every save would make
     * the cloud see a removal and an addition each time somebody corrected a name.
     */
    @Test
    void aBarcodeKeepsItsIdentityAcrossAnUnrelatedEdit() {
        Shop shop = fixtures.seed();
        String barcode = unique("BC");
        ProductRow product = admin.create(shop.tenantId(), draft(unique("SKU"), "Chilli Powder", 22_000, barcode));

        UUID before = barcodeUuid(barcode);

        admin.save(
                shop.tenantId(),
                product.id(),
                new ProductDraft(
                        null,
                        product.sku(),
                        "Chilli Powder 100g",
                        product.priceMinor(),
                        "INCLUSIVE",
                        1800,
                        null,
                        List.of(barcode),
                        null));

        assertThat(barcodeUuid(barcode)).isEqualTo(before);
    }

    private UUID barcodeUuid(String barcode) {
        return jdbc.queryForObject(
                "SELECT client_uuid FROM product_barcodes WHERE barcode = ?", UUID.class, barcode);
    }

    @Test
    void theSameBarcodeTwiceOnOneProductIsRefused() {
        Shop shop = fixtures.seed();
        String barcode = unique("BC");

        assertThatThrownBy(
                        () -> admin.create(shop.tenantId(), draft(unique("SKU"), "Soap", 18_500, barcode, barcode)))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("listed twice");
    }

    // -------------------------------------------------------------------------- refusals

    @Test
    void twoProductsCannotShareACodeEvenInDifferentLetterCase() {
        Shop shop = fixtures.seed();
        String sku = unique("SKU");
        admin.create(shop.tenantId(), draft(sku, "Dhal 1kg", 39_000));

        assertThatThrownBy(
                        () -> admin.create(shop.tenantId(), draft(sku.toLowerCase(), "Dhal 500g", 21_000)))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("already used");
    }

    /**
     * 18% typed as 18000 rather than 1800.
     *
     * <p>V100's CHECK only refuses a negative rate, so nothing below this catches it: the product
     * saves, prices correctly on the shelf, and then extracts almost the entire total as tax onto a
     * receipt the customer takes away.
     */
    @Test
    void aTaxRateAboveOneHundredPercentIsRefused() {
        Shop shop = fixtures.seed();

        assertThatThrownBy(
                        () ->
                                admin.create(
                                        shop.tenantId(),
                                        new ProductDraft(
                                                UUID.randomUUID(),
                                                unique("SKU"),
                                                "Mis-taxed",
                                                10_000,
                                                "INCLUSIVE",
                                                18_000,
                                                null,
                                                List.of(),
                                                null)))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("typo");
    }

    @Test
    void aNegativePriceIsRefused() {
        Shop shop = fixtures.seed();
        assertThatThrownBy(() -> admin.create(shop.tenantId(), draft(unique("SKU"), "Impossible", -1)))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("negative");
    }

    // ----------------------------------------------------------------- discontinuing

    /**
     * A discontinued product leaves the till and stays in the back office.
     *
     * <p>There is no delete anywhere in this service, because {@code sale_items.product_id} is a
     * foreign key and last quarter's receipt has to keep saying what was on it.
     */
    @Test
    void discontinuingHidesAProductFromTheTillButNotFromTheBackOffice() {
        Shop shop = fixtures.seed();
        String barcode = unique("BC");
        ProductRow product = admin.create(shop.tenantId(), draft(unique("SKU"), "Seasonal Sweets", 65_000, barcode));

        admin.setActive(shop.tenantId(), product.id(), false);

        assertThat(lookup.byBarcode(barcode)).isEmpty();
        assertThat(admin.list(shop.tenantId()))
                .filteredOn(row -> row.id() == product.id())
                .singleElement()
                .satisfies(row -> assertThat(row.active()).isFalse());

        admin.setActive(shop.tenantId(), product.id(), true);
        assertThat(lookup.byBarcode(barcode)).isPresent();
    }

    // ------------------------------------------------------------------------ categories

    /**
     * A rename is one row, and every product follows it.
     *
     * <p>This is the whole argument for V110 being a table rather than a text column: with a column
     * this would be an UPDATE across the catalogue that nobody remembers to run on the rows added
     * since.
     */
    @Test
    void renamingACategoryMovesEveryProductInItAtOnce() {
        Shop shop = fixtures.seed();
        CategoryRow beverages = admin.createCategory(shop.tenantId(), UUID.randomUUID(), unique("Beverages"));

        ProductRow tea =
                admin.create(
                        shop.tenantId(),
                        new ProductDraft(
                                UUID.randomUUID(),
                                unique("SKU"),
                                "Plain Tea",
                                30_000,
                                "INCLUSIVE",
                                1800,
                                beverages.id(),
                                List.of(),
                                null));
        assertThat(tea.categoryName()).isEqualTo(beverages.name());

        String renamed = unique("Drinks");
        admin.updateCategory(shop.tenantId(), beverages.id(), renamed, true);

        assertThat(admin.byId(shop.tenantId(), tea.id()).categoryName()).isEqualTo(renamed);
    }

    /** Case-folded, because "Beverages" and "beverages" are one aisle. */
    @Test
    void aCategoryNameCannotBeUsedTwice() {
        Shop shop = fixtures.seed();
        String name = unique("Household");
        admin.createCategory(shop.tenantId(), UUID.randomUUID(), name);

        assertThatThrownBy(
                        () -> admin.createCategory(shop.tenantId(), UUID.randomUUID(), name.toUpperCase()))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("already a category");
    }

    /**
     * A product may have no category.
     *
     * <p>Requiring one means a shop's first act is to invent a taxonomy, and what shops do under
     * that pressure is create a single category called "General" — the uncategorised state with
     * extra steps and a name to maintain.
     */
    @Test
    void aProductNeedsNoCategory() {
        Shop shop = fixtures.seed();
        ProductRow product = admin.create(shop.tenantId(), draft(unique("SKU"), "Loose Nails", 500));

        assertThat(product.categoryId()).isNull();
        assertThat(product.categoryName()).isNull();
    }

    /** The count is what the screen uses to answer "can I retire this one?". */
    @Test
    void aCategoryCarriesHowManyProductsSitInIt() {
        Shop shop = fixtures.seed();
        CategoryRow category = admin.createCategory(shop.tenantId(), UUID.randomUUID(), unique("Spices"));

        admin.create(
                shop.tenantId(),
                new ProductDraft(
                        UUID.randomUUID(),
                        unique("SKU"),
                        "Cardamom 50g",
                        88_000,
                        "INCLUSIVE",
                        1800,
                        category.id(),
                        List.of(),
                        null));

        assertThat(admin.categories(shop.tenantId()))
                .filteredOn(row -> row.id() == category.id())
                .singleElement()
                .satisfies(row -> assertThat(row.productCount()).isEqualTo(1));
    }
}

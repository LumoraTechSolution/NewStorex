package com.lumora.pos.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.product.ProductAdminService.ProductDraft;
import com.lumora.pos.product.ProductImportService.Action;
import com.lumora.pos.product.ProductImportService.ImportPlan;
import com.lumora.pos.product.ProductImportService.ImportRow;
import com.lumora.pos.testfixtures.ShopFixture;
import com.lumora.pos.testfixtures.ShopFixture.Shop;
import com.lumora.pos.web.RejectedException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Importing a product list (M3-03).
 *
 * <p>Reading the file is tested in {@code @lumora/domain}'s {@code csv.test.ts} — it is money math
 * and lives there. What is tested here is everything the file cannot know on its own: whether a
 * code already exists, who holds a barcode, which categories are new, and above all that a preview
 * writes <em>nothing</em> and a refused import leaves the catalogue exactly as it was.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
@Transactional
class ProductImportTest {

    @Autowired ProductImportService imports;
    @Autowired ProductAdminService admin;
    @Autowired ProductLookup lookup;
    @Autowired ShopFixture fixtures;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static ImportRow row(int line, String sku, String name, long priceMinor) {
        return new ImportRow(line, sku, name, priceMinor, "INCLUSIVE", 1800, null, List.of());
    }

    /** Applies a file the way the screens do: preview, then commit the hash it handed back. */
    private ImportPlan importFile(Shop shop, List<ImportRow> rows) {
        ImportPlan preview = imports.plan(shop.tenantId(), rows);
        return imports.apply(shop.tenantId(), rows, preview.planHash());
    }

    // -------------------------------------------------------------------- the dry run

    /**
     * The property the whole feature rests on: a preview changes nothing.
     *
     * <p>Including categories. Reporting a new category is easy to implement by creating it and
     * telling the caller, and then a preview somebody abandoned has quietly seeded the picker with
     * the typo they were about to spot.
     */
    @Test
    void aPreviewWritesNothingAtAll() {
        Shop shop = fixtures.seed();
        int productsBefore = admin.list(shop.tenantId()).size();
        int categoriesBefore = admin.categories(shop.tenantId()).size();

        ImportPlan plan =
                imports.plan(
                        shop.tenantId(),
                        List.of(
                                new ImportRow(
                                        2,
                                        unique("SKU"),
                                        "Cinnamon 100g",
                                        78_500,
                                        "INCLUSIVE",
                                        1800,
                                        unique("Spices"),
                                        List.of(unique("BC")))));

        assertThat(plan.creates()).isEqualTo(1);
        assertThat(plan.newCategories()).hasSize(1);
        assertThat(admin.list(shop.tenantId())).hasSize(productsBefore);
        assertThat(admin.categories(shop.tenantId())).hasSize(categoriesBefore);
    }

    /** A code the shop already has is an update, and the preview says which fields move. */
    @Test
    void anExistingCodeIsAnUpdateAndTheChangedFieldsAreNamed() {
        Shop shop = fixtures.seed();
        String sku = unique("SKU");
        admin.create(
                shop.tenantId(),
                new ProductDraft(
                        UUID.randomUUID(), sku, "Salt 1kg", 12_000, "INCLUSIVE", 1800, null, List.of()));

        ImportPlan plan = imports.plan(shop.tenantId(), List.of(row(2, sku, "Salt 1kg", 13_500)));

        assertThat(plan.updates()).isEqualTo(1);
        assertThat(plan.rows().get(0).action()).isEqualTo(Action.UPDATE);
        // Rendered, not counted: "1 product updated" is what a file that halves every price says too.
        assertThat(plan.rows().get(0).changes())
                .singleElement()
                .satisfies(
                        change -> {
                            assertThat(change.field()).isEqualTo("price");
                            assertThat(change.before()).isEqualTo("120.00");
                            assertThat(change.after()).isEqualTo("135.00");
                        });
    }

    /**
     * Re-importing the same file a second time is a no-op, and says so.
     *
     * <p>Shops do this constantly — the supplier resends the list, most of it unchanged. A preview
     * that reported four hundred updates would train them to stop reading it.
     */
    @Test
    void reimportingAnUnchangedFileChangesNothing() {
        Shop shop = fixtures.seed();
        List<ImportRow> file =
                List.of(
                        new ImportRow(
                                2,
                                unique("SKU"),
                                "Cardamom 50g",
                                88_000,
                                "INCLUSIVE",
                                1800,
                                null,
                                List.of(unique("BC"))));

        importFile(shop, file);
        ImportPlan second = imports.plan(shop.tenantId(), file);

        assertThat(second.unchanged()).isEqualTo(1);
        assertThat(second.creates()).isZero();
        assertThat(second.updates()).isZero();
    }

    /** Order is not a change. Otherwise every re-import buries the real edits in noise. */
    @Test
    void reorderingTheBarcodesOnARowIsNotAChange() {
        Shop shop = fixtures.seed();
        String sku = unique("SKU");
        String first = unique("BC");
        String second = unique("BC");

        importFile(
                shop,
                List.of(
                        new ImportRow(
                                2, sku, "Two Codes", 10_000, "INCLUSIVE", 1800, null, List.of(first, second))));

        ImportPlan plan =
                imports.plan(
                        shop.tenantId(),
                        List.of(
                                new ImportRow(
                                        2,
                                        sku,
                                        "Two Codes",
                                        10_000,
                                        "INCLUSIVE",
                                        1800,
                                        null,
                                        List.of(second, first))));

        assertThat(plan.unchanged()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------- refusals

    /**
     * One bad row refuses the whole file, and the good rows are not written either.
     *
     * <p>The alternative — import the good 380 of 400 — leaves a shop half-updated with no record
     * of which half, and the only way back is a spreadsheet diff nobody will do.
     */
    @Test
    void oneBadRowRefusesTheEntireFileAndWritesNothing() {
        Shop shop = fixtures.seed();
        String taken = unique("BC");
        admin.create(
                shop.tenantId(),
                new ProductDraft(
                        UUID.randomUUID(),
                        unique("SKU"),
                        "Tinned Fish",
                        45_000,
                        "INCLUSIVE",
                        1800,
                        null,
                        List.of(taken)));

        int before = admin.list(shop.tenantId()).size();
        String goodSku = unique("SKU");
        List<ImportRow> file =
                List.of(
                        row(2, goodSku, "Perfectly Fine", 10_000),
                        new ImportRow(
                                3,
                                unique("SKU"),
                                "Steals A Barcode",
                                20_000,
                                "INCLUSIVE",
                                1800,
                                null,
                                List.of(taken)));

        ImportPlan plan = imports.plan(shop.tenantId(), file);
        assertThat(plan.applicable()).isFalse();
        assertThat(plan.rows().get(1).problem()).contains("already on Tinned Fish");

        assertThatThrownBy(() -> imports.apply(shop.tenantId(), file, plan.planHash()))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("Nothing was changed");

        assertThat(admin.list(shop.tenantId())).hasSize(before);
    }

    /**
     * A barcode the row's own product already holds is not a conflict.
     *
     * <p>This is the ordinary case — re-importing a list the shop has already loaded — and getting
     * it wrong would make every second import fail on every barcoded product.
     */
    @Test
    void aProductKeepingItsOwnBarcodeIsNotAConflict() {
        Shop shop = fixtures.seed();
        String sku = unique("SKU");
        String barcode = unique("BC");

        importFile(
                shop,
                List.of(
                        new ImportRow(
                                2, sku, "Chilli Powder", 22_000, "INCLUSIVE", 1800, null, List.of(barcode))));

        ImportPlan again =
                imports.plan(
                        shop.tenantId(),
                        List.of(
                                new ImportRow(
                                        2,
                                        sku,
                                        "Chilli Powder 100g",
                                        22_000,
                                        "INCLUSIVE",
                                        1800,
                                        null,
                                        List.of(barcode))));

        assertThat(again.errors()).isZero();
        assertThat(again.updates()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------ the hash

    /**
     * A plan can only be applied as it was shown.
     *
     * <p>Between the preview and the confirmation somebody can pick a different file. Without the
     * check, the second file is imported under the first one's approval.
     */
    @Test
    void applyingADifferentFileThanTheOnePreviewedIsRefused() {
        Shop shop = fixtures.seed();
        List<ImportRow> shown = List.of(row(2, unique("SKU"), "What They Saw", 10_000));
        List<ImportRow> swapped = List.of(row(2, unique("SKU"), "What They Got", 999_000));

        String hash = imports.plan(shop.tenantId(), shown).planHash();

        assertThatThrownBy(() -> imports.apply(shop.tenantId(), swapped, hash))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("changed since this import was previewed");
    }

    /**
     * The hash covers the plan, not the file, so a catalogue that moved underneath also
     * invalidates it — the plan really would be a different plan.
     */
    @Test
    void aCatalogueThatMovedUnderneathAlsoInvalidatesThePreview() {
        Shop shop = fixtures.seed();
        String sku = unique("SKU");
        List<ImportRow> file = List.of(row(2, sku, "Late Arrival", 10_000));

        String hash = imports.plan(shop.tenantId(), file).planHash(); // planned as a CREATE

        // Somebody adds it by hand in the meantime, so the same file is now an UPDATE.
        admin.create(
                shop.tenantId(),
                new ProductDraft(
                        UUID.randomUUID(), sku, "Added By Hand", 55_000, "INCLUSIVE", 1800, null, List.of()));

        assertThatThrownBy(() -> imports.apply(shop.tenantId(), file, hash))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("changed since this import was previewed");
    }

    /** Row order does not change what the import does, so it must not invalidate the preview. */
    @Test
    void reorderingTheRowsDoesNotInvalidateThePreview() {
        Shop shop = fixtures.seed();
        ImportRow a = row(2, unique("SKU"), "First", 10_000);
        ImportRow b = row(3, unique("SKU"), "Second", 20_000);

        String hash = imports.plan(shop.tenantId(), List.of(a, b)).planHash();
        assertThat(imports.plan(shop.tenantId(), List.of(b, a)).planHash()).isEqualTo(hash);
    }

    // ------------------------------------------------------------------------- applying

    /** The whole point: an imported product is sellable. */
    @Test
    void anImportedProductScansAtTheTill() {
        Shop shop = fixtures.seed();
        String barcode = unique("BC");

        importFile(
                shop,
                List.of(
                        new ImportRow(
                                2,
                                unique("SKU"),
                                "Woodapple Jam 350g",
                                42_900,
                                "INCLUSIVE",
                                1800,
                                null,
                                List.of(barcode))));

        assertThat(lookup.byBarcode(barcode))
                .hasValueSatisfying(
                        summary -> {
                            assertThat(summary.name()).isEqualTo("Woodapple Jam 350g");
                            assertThat(summary.priceMinor()).isEqualTo(42_900);
                        });
    }

    /**
     * Categories named in the file are created on apply, once each.
     *
     * <p>Auto-creating them is what makes importing a fresh catalogue possible without typing a
     * taxonomy in by hand first. The safeguard against the typo V110 exists to prevent is that the
     * preview lists them, so "Bevarages" beside "Beverages" is visible before anyone confirms.
     */
    @Test
    void categoriesInTheFileAreCreatedOnceAndAttached() {
        Shop shop = fixtures.seed();
        String category = unique("Spices");
        String sku = unique("SKU");

        importFile(
                shop,
                List.of(
                        new ImportRow(2, sku, "Cinnamon", 78_500, "INCLUSIVE", 1800, category, List.of()),
                        new ImportRow(
                                3, unique("SKU"), "Cloves", 65_000, "INCLUSIVE", 1800, category, List.of())));

        assertThat(admin.categories(shop.tenantId()))
                .filteredOn(row -> row.name().equals(category))
                .singleElement()
                .satisfies(row -> assertThat(row.productCount()).isEqualTo(2));
    }

    /** An existing category is matched, not duplicated in a different case. */
    @Test
    void anExistingCategoryIsReusedRegardlessOfCase() {
        Shop shop = fixtures.seed();
        String name = unique("Household");
        admin.createCategory(shop.tenantId(), UUID.randomUUID(), name);
        int before = admin.categories(shop.tenantId()).size();

        List<ImportRow> file =
                List.of(
                        new ImportRow(
                                2,
                                unique("SKU"),
                                "Bleach 1L",
                                30_000,
                                "INCLUSIVE",
                                1800,
                                name.toUpperCase(),
                                List.of()));

        ImportPlan plan = imports.plan(shop.tenantId(), file);
        assertThat(plan.newCategories()).isEmpty();

        imports.apply(shop.tenantId(), file, plan.planHash());
        assertThat(admin.categories(shop.tenantId())).hasSize(before);
    }

    /**
     * The file's own duplicates are caught by the planner, not only by the parser.
     *
     * <p>The endpoint takes rows rather than a CSV, so the parser is not on the path of every
     * caller. Left to the database, the preview would report two clean creates and the apply would
     * fail halfway on a unique index.
     */
    @Test
    void twoRowsClaimingOneBarcodeAreRefusedByThePlan() {
        Shop shop = fixtures.seed();
        String barcode = unique("BC");

        ImportPlan plan =
                imports.plan(
                        shop.tenantId(),
                        List.of(
                                new ImportRow(
                                        2, unique("SKU"), "First", 10_000, "INCLUSIVE", 1800, null, List.of(barcode)),
                                new ImportRow(
                                        3,
                                        unique("SKU"),
                                        "Second",
                                        20_000,
                                        "INCLUSIVE",
                                        1800,
                                        null,
                                        List.of(barcode))));

        assertThat(plan.errors()).isEqualTo(1);
        assertThat(plan.rows().get(1).problem()).contains("line 2");
    }

    @Test
    void twoRowsWithTheSameProductCodeAreRefusedByThePlan() {
        Shop shop = fixtures.seed();
        String sku = unique("SKU");

        ImportPlan plan =
                imports.plan(shop.tenantId(), List.of(row(2, sku, "First", 10_000), row(3, sku, "Second", 20_000)));

        assertThat(plan.errors()).isEqualTo(1);
        assertThat(plan.rows().get(1).problem()).contains("line 2");
    }

    /**
     * An import never discontinues anything.
     *
     * <p>It is not a sync. A supplier's price list covers that supplier's goods, and treating an
     * absent row as a deletion would retire half a shop the first time one was loaded.
     */
    @Test
    void productsMissingFromTheFileAreLeftAlone() {
        Shop shop = fixtures.seed();
        String untouched = unique("SKU");
        ProductRow existing =
                admin.create(
                        shop.tenantId(),
                        new ProductDraft(
                                UUID.randomUUID(),
                                untouched,
                                "Not In The File",
                                10_000,
                                "INCLUSIVE",
                                1800,
                                null,
                                List.of()));

        importFile(shop, List.of(row(2, unique("SKU"), "Something Else", 20_000)));

        assertThat(admin.byId(shop.tenantId(), existing.id()).active()).isTrue();
        assertThat(admin.byId(shop.tenantId(), existing.id()).name()).isEqualTo("Not In The File");
    }
}

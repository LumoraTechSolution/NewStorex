package com.lumora.pos.product;

import com.lumora.pos.product.ProductAdminService.ProductDraft;
import com.lumora.pos.web.RejectedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Importing a product list (M3-03).
 *
 * <h2>The dry run is the feature</h2>
 *
 * A shopkeeper pasting a supplier's spreadsheet into their catalogue is making four hundred
 * decisions at once, and the ones that hurt are invisible: a price column read as VAT, a category
 * spelled two ways, an update where they expected a create. So {@link #plan} answers "what would
 * this do" against the real catalogue and writes nothing, and {@link #apply} will only run a plan
 * that has already been shown — see {@code planHash}.
 *
 * <h2>All or nothing</h2>
 *
 * One bad row refuses the whole file. Importing the good 380 of 400 leaves a shop half-updated with
 * no record of which half, and the only way back is a spreadsheet diff nobody will do. Refusing
 * everything costs one more edit-and-retry and keeps the catalogue in a state somebody understands.
 *
 * <h2>What an import is not</h2>
 *
 * It is not a sync. A product in the catalogue that the file does not mention is left exactly as it
 * is — not discontinued, not touched. A supplier's price list covers that supplier's goods, and
 * treating absence as deletion would retire half a shop the first time someone imported one.
 */
@Service
public class ProductImportService {

    private final ProductAdminService products;

    public ProductImportService(ProductAdminService products) {
        this.products = products;
    }

    // ------------------------------------------------------------------------- the shapes

    /**
     * One row, already read out of the file.
     *
     * <p>Parsed by {@code @lumora/domain}'s {@code parseProductCsv}, not here. The file holds
     * prices as text and turning text into minor units is money math, which §A says lives in
     * exactly one place — so the CSV never reaches Java, and this side never sees a decimal.
     */
    public record ImportRow(
            int line,
            String sku,
            String name,
            long priceMinor,
            String taxMode,
            int taxRateBp,
            String category,
            List<String> barcodes) {}

    /** What the import would do to one row, in the words the preview shows. */
    public record PlannedRow(
            int line,
            String sku,
            String name,
            Action action,
            List<FieldChange> changes,
            String problem) {}

    public enum Action {
        /** No product with this code — it will be added. */
        CREATE,
        /** The code exists and something about it differs. */
        UPDATE,
        /** The code exists and the row matches it exactly. Written anyway would be a no-op. */
        UNCHANGED,
        /** The row cannot be applied. One of these refuses the whole file. */
        ERROR
    }

    /**
     * A single field moving.
     *
     * <p>Rendered rather than counted, because "412 products updated" is not a preview — a price
     * list that quietly halves every price also reports 412 products updated.
     */
    public record FieldChange(String field, String before, String after) {}

    public record ImportPlan(
            List<PlannedRow> rows,
            int creates,
            int updates,
            int unchanged,
            int errors,
            /** Categories named in the file that do not exist yet. Created on apply. */
            List<String> newCategories,
            String planHash) {

        public boolean applicable() {
            return errors == 0;
        }
    }

    // ------------------------------------------------------------------------- planning

    /**
     * Works out what the file would do. Writes nothing.
     *
     * <p>Read-only and side-effect free, including for categories: a category the file names but
     * the shop does not have is reported in {@link ImportPlan#newCategories} rather than created,
     * so a preview that is never confirmed leaves no trace. That list is also the typo detector —
     * "Bevarages" appearing beside an existing "Beverages" is visible there and nowhere else.
     */
    @Transactional(readOnly = true)
    public ImportPlan plan(long tenantId, List<ImportRow> rows) {
        Map<String, ProductRow> bySku = new LinkedHashMap<>();
        Map<String, ProductRow> byBarcode = new LinkedHashMap<>();
        for (ProductRow product : products.list(tenantId)) {
            bySku.put(product.sku().toLowerCase(Locale.ROOT), product);
            for (String barcode : product.barcodes()) {
                byBarcode.put(barcode, product);
            }
        }

        Map<String, CategoryRow> byCategory = new LinkedHashMap<>();
        for (CategoryRow category : products.categories(tenantId)) {
            byCategory.put(category.name().trim().toLowerCase(Locale.ROOT), category);
        }

        List<PlannedRow> planned = new ArrayList<>(rows.size());
        LinkedHashSet<String> newCategories = new LinkedHashSet<>();

        // Duplicates *within* the file. The CSV parser catches these too, but this endpoint takes
        // rows rather than a file, so the parser is not on every caller's path. Without it the
        // preview reports two clean creates and the apply fails halfway on a unique index — which
        // rolls back correctly and still means the preview lied.
        Map<String, Integer> skuFirstSeen = new LinkedHashMap<>();
        Map<String, Integer> barcodeFirstSeen = new LinkedHashMap<>();

        for (ImportRow row : rows) {
            String problem = repeatedInFile(row, skuFirstSeen, barcodeFirstSeen);
            if (problem == null) {
                problem = problemWith(row, bySku, byBarcode);
            }
            if (problem != null) {
                planned.add(new PlannedRow(row.line(), row.sku(), row.name(), Action.ERROR, List.of(), problem));
                continue;
            }

            if (row.category() != null
                    && !byCategory.containsKey(row.category().trim().toLowerCase(Locale.ROOT))) {
                newCategories.add(row.category().trim());
            }

            ProductRow existing = bySku.get(row.sku().toLowerCase(Locale.ROOT));
            if (existing == null) {
                planned.add(new PlannedRow(row.line(), row.sku(), row.name(), Action.CREATE, List.of(), null));
                continue;
            }

            List<FieldChange> changes = diff(existing, row);
            planned.add(
                    new PlannedRow(
                            row.line(),
                            row.sku(),
                            row.name(),
                            changes.isEmpty() ? Action.UNCHANGED : Action.UPDATE,
                            changes,
                            null));
        }

        int creates = (int) planned.stream().filter(r -> r.action() == Action.CREATE).count();
        int updates = (int) planned.stream().filter(r -> r.action() == Action.UPDATE).count();
        int unchanged = (int) planned.stream().filter(r -> r.action() == Action.UNCHANGED).count();
        int errors = (int) planned.stream().filter(r -> r.action() == Action.ERROR).count();

        return new ImportPlan(
                planned,
                creates,
                updates,
                unchanged,
                errors,
                List.copyOf(newCategories),
                hash(planned, newCategories));
    }

    /**
     * A code or a barcode used twice in the same file.
     *
     * <p>Names the earlier line, because the fix is always to reconcile the two rows and a message
     * that mentions only one of them sends the shopkeeper looking for the other.
     *
     * <p>Registers the row's codes as it goes, so the first occurrence is clean and only the
     * repeats are flagged — otherwise both lines error and the file looks twice as broken as it is.
     */
    private String repeatedInFile(
            ImportRow row, Map<String, Integer> skuFirstSeen, Map<String, Integer> barcodeFirstSeen) {
        if (row.sku() != null && !row.sku().isBlank()) {
            Integer firstLine = skuFirstSeen.putIfAbsent(row.sku().toLowerCase(Locale.ROOT), row.line());
            if (firstLine != null) {
                return "The product code " + row.sku() + " is also on line " + firstLine + ".";
            }
        }
        for (String barcode : row.barcodes()) {
            Integer firstLine = barcodeFirstSeen.putIfAbsent(barcode, row.line());
            if (firstLine != null) {
                return "The barcode " + barcode + " is also on line " + firstLine + ".";
            }
        }
        return null;
    }

    /**
     * Everything that stops a row being applied, in the order a person would notice it.
     *
     * <p>Returns the first problem rather than all of them. A row with a blank name and a stolen
     * barcode has one thing wrong with it as far as the shopkeeper is concerned — the row — and
     * listing two faults per line makes a forty-row report unreadable.
     */
    private String problemWith(
            ImportRow row, Map<String, ProductRow> bySku, Map<String, ProductRow> byBarcode) {
        if (row.sku() == null || row.sku().isBlank()) {
            return "No product code.";
        }
        if (row.name() == null || row.name().isBlank()) {
            return "No name.";
        }
        if (row.priceMinor() < 0) {
            return "The price is negative.";
        }
        if (row.taxRateBp() < 0 || row.taxRateBp() > 10_000) {
            return "The VAT rate is not a percentage between 0 and 100.";
        }
        if (!"INCLUSIVE".equals(row.taxMode()) && !"EXCLUSIVE".equals(row.taxMode())) {
            return "Tax mode must be INCLUSIVE or EXCLUSIVE.";
        }

        ProductRow sameSku = bySku.get(row.sku().toLowerCase(Locale.ROOT));
        for (String barcode : row.barcodes()) {
            ProductRow holder = byBarcode.get(barcode);
            // Held by a different product than the one this row is updating. Held by the *same*
            // product is the ordinary case: a re-import of a list the shop already loaded.
            if (holder != null && (sameSku == null || holder.id() != sameSku.id())) {
                return "The barcode "
                        + barcode
                        + " is already on "
                        + holder.name()
                        + " ("
                        + holder.sku()
                        + ").";
            }
        }
        return null;
    }

    /**
     * What actually moves, field by field.
     *
     * <p>Barcodes compare as sets: the file listing them in a different order is not a change, and
     * reporting it as one would bury the price changes in noise on every re-import.
     */
    private List<FieldChange> diff(ProductRow existing, ImportRow row) {
        List<FieldChange> changes = new ArrayList<>();

        if (!existing.name().equals(row.name())) {
            changes.add(new FieldChange("name", existing.name(), row.name()));
        }
        if (existing.priceMinor() != row.priceMinor()) {
            changes.add(
                    new FieldChange(
                            "price", money(existing.priceMinor()), money(row.priceMinor())));
        }
        if (existing.taxRateBp() != row.taxRateBp()) {
            changes.add(
                    new FieldChange("VAT", percent(existing.taxRateBp()), percent(row.taxRateBp())));
        }
        if (!existing.taxMode().equals(row.taxMode())) {
            changes.add(new FieldChange("tax mode", existing.taxMode(), row.taxMode()));
        }

        String before = existing.categoryName() == null ? "" : existing.categoryName();
        String after = row.category() == null ? "" : row.category().trim();
        if (!before.equalsIgnoreCase(after)) {
            changes.add(new FieldChange("category", blankAsDash(before), blankAsDash(after)));
        }

        LinkedHashSet<String> had = new LinkedHashSet<>(existing.barcodes());
        LinkedHashSet<String> wants = new LinkedHashSet<>(row.barcodes());
        if (!had.equals(wants)) {
            changes.add(
                    new FieldChange(
                            "barcodes", blankAsDash(String.join(" ", had)), blankAsDash(String.join(" ", wants))));
        }

        return changes;
    }

    // -------------------------------------------------------------------------- applying

    /**
     * Applies a plan the caller has already seen.
     *
     * <p>{@code expectedPlanHash} is not ceremony. Between the preview and the confirmation the
     * shopkeeper can pick a different file, and somebody at the till can change a price — either
     * makes the confirmed plan a different plan from the shown one. The hash covers the plan, not
     * just the rows, so a catalogue that moved underneath also invalidates it and the answer is
     * "look again" rather than a silent surprise.
     *
     * <p>One transaction. A file that fails halfway leaves nothing behind.
     */
    @Transactional
    public ImportPlan apply(long tenantId, List<ImportRow> rows, String expectedPlanHash) {
        ImportPlan plan = plan(tenantId, rows);

        if (!plan.planHash().equals(expectedPlanHash)) {
            throw new RejectedException(
                    "The catalogue or the file changed since this import was previewed. "
                            + "Check the preview again before importing.");
        }
        if (!plan.applicable()) {
            throw new RejectedException(
                    "This file has "
                            + plan.errors()
                            + " row(s) that cannot be imported. Nothing was changed — fix them and"
                            + " try again.");
        }

        Map<String, Long> categoryIds = new LinkedHashMap<>();
        for (CategoryRow category : products.categories(tenantId)) {
            categoryIds.put(category.name().trim().toLowerCase(Locale.ROOT), category.id());
        }
        for (String name : plan.newCategories()) {
            CategoryRow created = products.createCategory(tenantId, UUID.randomUUID(), name);
            categoryIds.put(created.name().trim().toLowerCase(Locale.ROOT), created.id());
        }

        Map<String, Long> idsBySku = new LinkedHashMap<>();
        for (ProductRow product : products.list(tenantId)) {
            idsBySku.put(product.sku().toLowerCase(Locale.ROOT), product.id());
        }

        Map<Integer, ImportRow> byLine = new LinkedHashMap<>();
        for (ImportRow row : rows) {
            byLine.put(row.line(), row);
        }

        for (PlannedRow planned : plan.rows()) {
            if (planned.action() == Action.UNCHANGED) {
                continue;
            }
            ImportRow row = byLine.get(planned.line());
            Long categoryId =
                    row.category() == null
                            ? null
                            : categoryIds.get(row.category().trim().toLowerCase(Locale.ROOT));

            ProductDraft draft =
                    new ProductDraft(
                            UUID.randomUUID(),
                            row.sku(),
                            row.name(),
                            row.priceMinor(),
                            row.taxMode(),
                            row.taxRateBp(),
                            categoryId,
                            row.barcodes());

            // Through the same service the back-office form uses, deliberately. A second write
            // path would be a second place for the barcode rules to be enforced, and the one that
            // gets forgotten is always the one nobody looks at.
            if (planned.action() == Action.CREATE) {
                products.create(tenantId, draft);
            } else {
                products.save(tenantId, idsBySku.get(row.sku().toLowerCase(Locale.ROOT)), draft);
            }
        }

        return plan;
    }

    // -------------------------------------------------------------------------- helpers

    /**
     * A stable fingerprint of the plan.
     *
     * <p>Over the decided actions and the changes, not over the file's bytes: two files differing
     * only in row order or line endings describe the same import and should not need a second
     * look, while the same file against a moved catalogue should.
     */
    private static String hash(List<PlannedRow> rows, LinkedHashSet<String> newCategories) {
        StringBuilder canonical = new StringBuilder();
        rows.stream()
                .sorted((a, b) -> a.sku().compareToIgnoreCase(b.sku()))
                .forEach(
                        row -> {
                            canonical
                                    .append(row.sku().toLowerCase(Locale.ROOT))
                                    .append(SEP)
                                    .append(row.action())
                                    .append(SEP)
                                    .append(row.problem() == null ? "" : row.problem());
                            for (FieldChange change : row.changes()) {
                                canonical
                                        .append(SEP)
                                        .append(change.field())
                                        .append('>')
                                        .append(change.after());
                            }
                            canonical.append(RECORD_SEP);
                        });
        newCategories.stream().sorted().forEach(name -> canonical.append(name).append(RECORD_SEP));

        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    /**
     * ASCII unit and record separators, so a field's own text cannot forge a boundary: a product
     * genuinely named {@code A>B} must not hash the same as a field change to {@code B}.
     */
    private static final char SEP = '\u001f';

    private static final char RECORD_SEP = '\u001e';

    /** Minor units as a person reads them. Display only — nothing computes from this. */
    private static String money(long minorUnits) {
        long absolute = Math.abs(minorUnits);
        return "%s%d.%02d".formatted(minorUnits < 0 ? "-" : "", absolute / 100, absolute % 100);
    }

    private static String percent(int basisPoints) {
        int absolute = Math.abs(basisPoints);
        return "%s%d.%02d%%".formatted(basisPoints < 0 ? "-" : "", absolute / 100, absolute % 100);
    }

    private static String blankAsDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}

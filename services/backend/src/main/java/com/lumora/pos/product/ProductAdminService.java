package com.lumora.pos.product;

import com.lumora.pos.outbox.OutboxWriter;
import com.lumora.pos.web.RejectedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Editing the catalogue (M3-02).
 *
 * <p>The write half of {@link ProductLookup}, kept apart from it on purpose. The lookup is on the
 * critical path of every item sold and is read-only by construction; this is used a few times a
 * week, sitting down, and every method here is a transaction that can refuse. Mixing them would put
 * a service that validates and throws in front of the query a scan waits on.
 *
 * <h2>What this cannot do</h2>
 *
 * There is no delete. {@code sale_items.product_id} is a foreign key, so a product that has ever
 * been sold cannot be removed without either failing the constraint or loosening it — and then last
 * quarter's receipt no longer says what was on it. {@code active = false} takes a product out of the
 * till's search and off the picker, and leaves history able to resolve.
 *
 * <h2>Barcodes are set, not appended</h2>
 *
 * {@link #save} takes the complete list a product should carry and makes the table match: codes that
 * vanished are deleted, new ones inserted, and the first in the list becomes the primary. Add-one
 * and remove-one endpoints were the alternative, and they make the screen's Save button a lie — an
 * owner who removes a row from a form and then abandons it expects the code to still scan.
 *
 * <p>The one rule V103 exists to protect is that a barcode resolves to exactly one product. The
 * unique index enforces it; this checks first anyway, so the refusal can name the product already
 * holding the code instead of surfacing a constraint violation.
 */
@Service
public class ProductAdminService {

    private static final int MAX_TAX_RATE_BP = 10_000;

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;

    public ProductAdminService(JdbcTemplate jdbc, OutboxWriter outbox) {
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    /**
     * What the caller wants a product to be. One shape for a create and an edit, because they
     * validate identically and a second record would drift.
     */
    public record ProductDraft(
            UUID clientUuid,
            String sku,
            String name,
            long priceMinor,
            String taxMode,
            int taxRateBp,
            Long categoryId,
            List<String> barcodes,
            /** Null means nobody is watching this product. Zero is a real threshold — see V120. */
            Integer reorderPoint) {}

    // ------------------------------------------------------------------------- products

    /**
     * Every product, discontinued ones included.
     *
     * <p>Unlike {@link ProductLookup#active()} this does not filter. The back office is the only
     * place a product can be reinstated, and a screen that hides what it alone can change has a
     * permanent dead end in it.
     */
    @Transactional(readOnly = true)
    public List<ProductRow> list(long tenantId) {
        return jdbc.query(
                SELECT_ROW + " WHERE p.tenant_id = ? GROUP BY p.id, c.id ORDER BY p.active DESC, p.name",
                ROW,
                tenantId);
    }

    @Transactional(readOnly = true)
    public ProductRow byId(long tenantId, long productId) {
        try {
            return jdbc.queryForObject(
                    SELECT_ROW + " WHERE p.tenant_id = ? AND p.id = ? GROUP BY p.id, c.id",
                    ROW,
                    tenantId,
                    productId);
        } catch (EmptyResultDataAccessException e) {
            throw new RejectedException("No such product");
        }
    }

    /**
     * Creates a product.
     *
     * <p>{@code clientUuid} comes from the caller, as it does for every other aggregate here, so
     * that a retried request or a double-pressed key is one product rather than two differing only
     * by a suffix on the SKU.
     */
    @Transactional
    public ProductRow create(long tenantId, ProductDraft draft) {
        String sku = requireSku(draft.sku());
        String name = requireName(draft.name());
        requirePrice(draft.priceMinor());
        String taxMode = requireTaxMode(draft.taxMode());
        requireTaxRate(draft.taxRateBp());
        Integer reorderPoint = requireReorderPoint(draft.reorderPoint());
        List<String> barcodes = cleanBarcodes(draft.barcodes());
        requireCategoryBelongsHere(tenantId, draft.categoryId());
        refuseDuplicateSku(tenantId, sku, null);

        Long id =
                jdbc.queryForObject(
                        """
                        INSERT INTO products
                            (client_uuid, tenant_id, sku, name, price_minor, tax_mode, tax_rate_bp,
                             category_id, reorder_point)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (client_uuid) DO UPDATE SET client_uuid = excluded.client_uuid
                        RETURNING id
                        """,
                        Long.class,
                        draft.clientUuid(),
                        tenantId,
                        sku,
                        name,
                        draft.priceMinor(),
                        taxMode,
                        draft.taxRateBp(),
                        draft.categoryId(),
                        reorderPoint);

        setBarcodes(tenantId, id, barcodes);
        ProductRow created = byId(tenantId, id);
        enqueue(tenantId, created);
        return created;
    }

    /**
     * Edits a product in place.
     *
     * <p>Everything except {@code active}, which is {@link #setActive} — for the same reason a
     * user's PIN is not a field on their rename form. Retiring a line carries a different weight
     * from correcting its price, and one form holding both will eventually retire something during
     * an edit that only meant to fix a name.
     */
    @Transactional
    public ProductRow save(long tenantId, long productId, ProductDraft draft) {
        String sku = requireSku(draft.sku());
        String name = requireName(draft.name());
        requirePrice(draft.priceMinor());
        String taxMode = requireTaxMode(draft.taxMode());
        requireTaxRate(draft.taxRateBp());
        Integer reorderPoint = requireReorderPoint(draft.reorderPoint());
        List<String> barcodes = cleanBarcodes(draft.barcodes());
        requireCategoryBelongsHere(tenantId, draft.categoryId());

        byId(tenantId, productId); // refuses before anything is written
        refuseDuplicateSku(tenantId, sku, productId);

        jdbc.update(
                """
                UPDATE products
                   SET sku = ?, name = ?, price_minor = ?, tax_mode = ?, tax_rate_bp = ?,
                       category_id = ?, reorder_point = ?
                 WHERE tenant_id = ? AND id = ?
                """,
                sku,
                name,
                draft.priceMinor(),
                taxMode,
                draft.taxRateBp(),
                draft.categoryId(),
                reorderPoint,
                tenantId,
                productId);

        setBarcodes(tenantId, productId, barcodes);
        ProductRow saved = byId(tenantId, productId);
        enqueue(tenantId, saved);
        return saved;
    }

    /** Discontinues a product or brings it back. There is no delete — see the class comment. */
    @Transactional
    public ProductRow setActive(long tenantId, long productId, boolean active) {
        byId(tenantId, productId);
        jdbc.update(
                "UPDATE products SET active = ? WHERE tenant_id = ? AND id = ?",
                active,
                tenantId,
                productId);
        ProductRow updated = byId(tenantId, productId);
        enqueue(tenantId, updated);
        return updated;
    }

    // ------------------------------------------------------------------------ barcodes

    /**
     * Makes {@code product_barcodes} match the list exactly.
     *
     * <p>The order of operations matters. Every {@code is_primary} is cleared first, because
     * {@code ux_product_barcodes_primary} allows one per product and setting the new primary before
     * clearing the old one collides with a code that is about to lose the flag anyway.
     *
     * <p>Codes that survive the edit are updated rather than deleted and reinserted, so a barcode
     * keeps its {@code client_uuid} across an unrelated price change. M3-12 syncs these rows; churning
     * their identity on every save would make the cloud see a delete and an insert where nothing
     * about the code changed.
     */
    private void setBarcodes(long tenantId, long productId, List<String> barcodes) {
        refuseBarcodesHeldElsewhere(tenantId, productId, barcodes);

        jdbc.update("UPDATE product_barcodes SET is_primary = false WHERE product_id = ?", productId);

        List<String> existing =
                jdbc.queryForList(
                        "SELECT barcode FROM product_barcodes WHERE product_id = ?",
                        String.class,
                        productId);

        for (String gone : existing) {
            if (!barcodes.contains(gone)) {
                jdbc.update(
                        "DELETE FROM product_barcodes WHERE product_id = ? AND barcode = ?",
                        productId,
                        gone);
            }
        }

        for (int i = 0; i < barcodes.size(); i++) {
            String barcode = barcodes.get(i);
            boolean primary = i == 0;
            if (existing.contains(barcode)) {
                jdbc.update(
                        "UPDATE product_barcodes SET is_primary = ? WHERE product_id = ? AND barcode = ?",
                        primary,
                        productId,
                        barcode);
            } else {
                jdbc.update(
                        """
                        INSERT INTO product_barcodes
                            (client_uuid, tenant_id, product_id, barcode, is_primary)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        tenantId,
                        productId,
                        barcode,
                        primary);
            }
        }
    }

    /**
     * The check that turns a constraint violation into a sentence.
     *
     * <p>{@code ux_product_barcodes_tenant_code} would refuse this anyway, with a message naming an
     * index. The person typing is holding a packet whose code will not scan, and what they need to
     * know is which product already claims it — nine times in ten the answer is that the same goods
     * were entered twice under two SKUs.
     */
    private void refuseBarcodesHeldElsewhere(long tenantId, long productId, List<String> barcodes) {
        for (String barcode : barcodes) {
            List<Map<String, Object>> holder =
                    jdbc.queryForList(
                            """
                            SELECT p.name, p.sku
                              FROM product_barcodes b
                              JOIN products p ON p.id = b.product_id
                             WHERE b.tenant_id = ? AND b.barcode = ? AND b.product_id <> ?
                            """,
                            tenantId,
                            barcode,
                            productId);
            if (!holder.isEmpty()) {
                throw new RejectedException(
                        "The barcode "
                                + barcode
                                + " is already on "
                                + holder.get(0).get("name")
                                + " ("
                                + holder.get(0).get("sku")
                                + "). A barcode belongs to one product only — that is what makes a"
                                + " scan a single keystroke.");
            }
        }
    }

    // ---------------------------------------------------------------------- categories

    @Transactional(readOnly = true)
    public List<CategoryRow> categories(long tenantId) {
        return jdbc.query(
                """
                SELECT c.id, c.client_uuid, c.name, c.active,
                       (SELECT count(*) FROM products p WHERE p.category_id = c.id) AS product_count
                  FROM product_categories c
                 WHERE c.tenant_id = ?
                 ORDER BY c.active DESC, lower(c.name)
                """,
                (rs, row) ->
                        new CategoryRow(
                                rs.getLong("id"),
                                rs.getObject("client_uuid", UUID.class),
                                rs.getString("name"),
                                rs.getBoolean("active"),
                                rs.getInt("product_count")),
                tenantId);
    }

    @Transactional
    public CategoryRow createCategory(long tenantId, UUID clientUuid, String name) {
        String clean = requireCategoryName(name);
        refuseDuplicateCategory(tenantId, clean, null);
        Long id =
                jdbc.queryForObject(
                        """
                        INSERT INTO product_categories (client_uuid, tenant_id, name)
                        VALUES (?, ?, ?)
                        ON CONFLICT (client_uuid) DO UPDATE SET client_uuid = excluded.client_uuid
                        RETURNING id
                        """,
                        Long.class,
                        clientUuid,
                        tenantId,
                        clean);
        return category(tenantId, id);
    }

    /**
     * Renames a category, or retires it.
     *
     * <p>A rename is one row and every product follows it, which is the entire argument for this
     * being a table rather than a text column (see V110). Retiring one leaves the products where
     * they are: they keep reporting under the category they were sold under, and only the picker
     * stops offering it.
     */
    @Transactional
    public CategoryRow updateCategory(long tenantId, long categoryId, String name, boolean active) {
        String clean = requireCategoryName(name);
        category(tenantId, categoryId);
        refuseDuplicateCategory(tenantId, clean, categoryId);
        jdbc.update(
                "UPDATE product_categories SET name = ?, active = ? WHERE tenant_id = ? AND id = ?",
                clean,
                active,
                tenantId,
                categoryId);
        return category(tenantId, categoryId);
    }

    private CategoryRow category(long tenantId, long categoryId) {
        return categories(tenantId).stream()
                .filter(c -> c.id() == categoryId)
                .findFirst()
                .orElseThrow(() -> new RejectedException("No such category"));
    }

    // -------------------------------------------------------------------------- guards

    private void refuseDuplicateSku(long tenantId, String sku, Long allowedId) {
        List<Long> clash =
                jdbc.queryForList(
                        "SELECT id FROM products WHERE tenant_id = ? AND lower(sku) = lower(?)",
                        Long.class,
                        tenantId,
                        sku);
        for (Long id : clash) {
            if (allowedId == null || id.longValue() != allowedId.longValue()) {
                throw new RejectedException(
                        "The code " + sku + " is already used by another product");
            }
        }
    }

    private void refuseDuplicateCategory(long tenantId, String name, Long allowedId) {
        List<Long> clash =
                jdbc.queryForList(
                        """
                        SELECT id FROM product_categories
                         WHERE tenant_id = ? AND lower(btrim(name)) = lower(btrim(?))
                        """,
                        Long.class,
                        tenantId,
                        name);
        for (Long id : clash) {
            if (allowedId == null || id.longValue() != allowedId.longValue()) {
                throw new RejectedException("There is already a category called " + name);
            }
        }
    }

    private void requireCategoryBelongsHere(long tenantId, Long categoryId) {
        if (categoryId == null) {
            return;
        }
        Integer found =
                jdbc.queryForObject(
                        "SELECT count(*) FROM product_categories WHERE tenant_id = ? AND id = ?",
                        Integer.class,
                        tenantId,
                        categoryId);
        if (found == null || found == 0) {
            throw new RejectedException("No such category");
        }
    }

    /**
     * The shop's own code for the product.
     *
     * <p>Compared case-insensitively even though {@code ux_products_tenant_sku} is exact. {@code
     * TEA-400} and {@code tea-400} are one product to everybody who works there, and letting both
     * exist means a stocktake counts one of them while a report totals the other.
     */
    private static String requireSku(String sku) {
        if (sku == null || sku.trim().isEmpty()) {
            throw new RejectedException("A product needs a code");
        }
        String trimmed = sku.trim();
        if (trimmed.length() > 64) {
            throw new RejectedException("A product code must be 64 characters or fewer");
        }
        return trimmed;
    }

    private static String requireName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new RejectedException("A product needs a name");
        }
        return name.trim();
    }

    private static void requirePrice(long priceMinor) {
        if (priceMinor < 0) {
            throw new RejectedException("A price cannot be negative");
        }
    }

    /**
     * Checks a reorder threshold, and deliberately lets null through.
     *
     * <p>Null is "nobody is watching this product" and is the default for the whole catalogue
     * (V120). Zero is a different and equally valid answer — a shopkeeper asking to hear about a
     * line the moment it is empty — so this must not fold one into the other. Only a negative is
     * rejected: there is no shelf you would want to be told about before it reaches below empty.
     */
    private static Integer requireReorderPoint(Integer reorderPoint) {
        if (reorderPoint != null && reorderPoint < 0) {
            throw new RejectedException(
                    "A reorder point cannot be negative. Leave it blank not to watch this product,"
                            + " or set 0 to be told when it runs out.");
        }
        return reorderPoint;
    }

    /** Null-preserving, because 0 and "not watched" are different facts — see {@link #requireReorderPoint}. */
    private static Integer reorderPoint(java.sql.ResultSet rs) throws java.sql.SQLException {
        int value = rs.getInt("reorder_point");
        return rs.wasNull() ? null : value;
    }

    private static String requireTaxMode(String taxMode) {
        String upper = taxMode == null ? "" : taxMode.trim().toUpperCase(Locale.ROOT);
        if (!upper.equals("INCLUSIVE") && !upper.equals("EXCLUSIVE")) {
            throw new RejectedException("Tax mode must be INCLUSIVE or EXCLUSIVE");
        }
        return upper;
    }

    /**
     * Basis points, so 18% VAT is 1800.
     *
     * <p>Capped at 100%. V100's CHECK only refuses a negative rate, and the mistake that actually
     * happens is typing 18% as 18000 — which prices nothing wrongly until a sale is rung up, and
     * then extracts almost the whole total as tax onto a receipt the customer takes away.
     */
    private static void requireTaxRate(int taxRateBp) {
        if (taxRateBp < 0) {
            throw new RejectedException("A tax rate cannot be negative");
        }
        if (taxRateBp > MAX_TAX_RATE_BP) {
            throw new RejectedException(
                    "A tax rate above 100% is a typo — 18% VAT is 1800 basis points, not 18000");
        }
    }

    private static String requireCategoryName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new RejectedException("A category needs a name");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 64) {
            throw new RejectedException("A category name must be 64 characters or fewer");
        }
        return trimmed;
    }

    /**
     * Trims, drops blanks, and refuses the same code twice on one product.
     *
     * <p>A repeated code would be harmless — the second write is a no-op — but it means the form is
     * showing something other than what gets saved, and an owner who typed it twice by mistake
     * should be told rather than quietly corrected.
     */
    private static List<String> cleanBarcodes(List<String> barcodes) {
        if (barcodes == null) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> clean = new ArrayList<>();
        for (String raw : barcodes) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String barcode = raw.trim();
            if (!seen.add(barcode)) {
                throw new RejectedException("The barcode " + barcode + " is listed twice");
            }
            clean.add(barcode);
        }
        return clean;
    }

    // ------------------------------------------------------------------------- mapping

    private static final String SELECT_ROW =
            """
            SELECT p.id, p.client_uuid, p.sku, p.name, p.price_minor, p.tax_mode, p.tax_rate_bp,
                   p.active, p.category_id, p.reorder_point, c.name AS category_name,
                   COALESCE(
                       array_agg(b.barcode ORDER BY b.is_primary DESC, b.barcode)
                           FILTER (WHERE b.barcode IS NOT NULL),
                       '{}') AS barcodes
              FROM products p
              LEFT JOIN product_barcodes b ON b.product_id = p.id
              LEFT JOIN product_categories c ON c.id = p.category_id
            """;

    private static final RowMapper<ProductRow> ROW =
            (rs, row) -> {
                long categoryId = rs.getLong("category_id");
                Long category = rs.wasNull() ? null : categoryId;
                Object[] codes = (Object[]) rs.getArray("barcodes").getArray();
                List<String> barcodes = new ArrayList<>(codes.length);
                for (Object code : codes) {
                    barcodes.add((String) code);
                }
                return new ProductRow(
                        rs.getLong("id"),
                        rs.getObject("client_uuid", UUID.class),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getLong("price_minor"),
                        rs.getString("tax_mode"),
                        rs.getInt("tax_rate_bp"),
                        category,
                        rs.getString("category_name"),
                        barcodes,
                        reorderPoint(rs),
                        rs.getBoolean("active"));
            };

    // ------------------------------------------------------------------------- sync (M3-12)

    /**
     * Puts the product on the outbox, in the caller's transaction.
     *
     * <h2>The whole row, every time</h2>
     *
     * Not a diff. The cloud's copy is a mirror of what the shop holds now, so shipping the state
     * rather than the change means redelivery is a no-op and arrival order does not matter — the
     * same property {@code client_uuid} buys everywhere else in this system, extended to an
     * aggregate that is genuinely mutable. A stream of changes would need to arrive in order, and
     * an offline shop's backlog has no order worth relying on.
     *
     * <h2>Barcodes travel with it and replace what was there</h2>
     *
     * A barcode removed at the shop has to disappear from the cloud too, and a merge cannot express
     * a removal — so the list is authoritative and the ingest deletes what is not in it. The first
     * entry is the primary one, which is the order {@code SELECT_ROW} already produces.
     */
    private void enqueue(long tenantId, ProductRow product) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientUuid", product.clientUuid().toString());
        payload.put("sku", product.sku());
        payload.put("name", product.name());
        payload.put("priceMinor", product.priceMinor());
        payload.put("taxMode", product.taxMode());
        payload.put("taxRateBp", product.taxRateBp());
        // The category as a name. The cloud has no categories table and needs none — the console
        // groups by the string, and a join table for a label nobody edits from there is a second
        // thing to keep in step for no reader's benefit.
        payload.put("category", product.categoryName());
        // Null travels as null and is stored as null (V211). It is not the same as 0.
        payload.put("reorderPoint", product.reorderPoint());
        payload.put("active", product.active());
        payload.put("barcodes", List.copyOf(product.barcodes()));
        outbox.enqueue(tenantId, "product", product.clientUuid(), payload);
    }
}

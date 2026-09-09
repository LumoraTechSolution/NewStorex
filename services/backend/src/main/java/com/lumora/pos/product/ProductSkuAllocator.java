package com.lumora.pos.product;

import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Makes up a product code when the shopkeeper does not supply one.
 *
 * <p>Adding a product used to mean inventing a code for it, on every product, before the form
 * would save. Left blank now, the code is allocated here from the product's category:
 * {@code BEV-001} for the first beverage, {@code BEV-002} for the next, {@code P-0001} for
 * something not in a category yet. A code the shopkeeper types is never touched — this runs only
 * when the field came in empty.
 *
 * <h2>Why a counter table and not a query over products</h2>
 *
 * The cheaper-looking thing is {@code SELECT max(sku) ... WHERE sku LIKE 'BEV-%'}, and it is wrong
 * twice. {@link ProductAdminService#save} rewrites a product's code, so a rename removes the
 * highest number in a prefix without any delete being involved — and the next create then reissues
 * a code the shop has already printed on a shelf label and re-imports its CSV by. And read-then-
 * write is a race that ends in a unique-index violation, which {@link ProductAdminService#create}
 * cannot catch: it is {@code @Transactional}, and a constraint violation aborts the transaction,
 * leaving nothing to read the winner back with. That is the same reasoning
 * {@link com.lumora.pos.cash.CashMovementService} records for {@code client_uuid}.
 *
 * <h2>Prefixes are allowed to collide</h2>
 *
 * "Beverages" and "Beverage" both yield {@code BEV} and share one counter, so the codes stay
 * unique and only the hint is lost. Disambiguating them — {@code BEV2-001} — was the alternative,
 * and it makes renaming a category change the prefix of everything added afterwards, leaving one
 * aisle with two prefixes forever. The prefix is a hint, not a key.
 *
 * <p>Like {@link com.lumora.pos.outbox.OutboxWriter} and {@link
 * com.lumora.pos.invoice.InvoiceNumberAllocator}, this deliberately has no transaction of its own:
 * the number is allocated inside the caller's, so a create that is refused afterwards does not burn
 * one. Retries and rollbacks still leave gaps — {@code BEV-001} then {@code BEV-003} — and that is
 * fine. Nothing audits a product code for missing entries the way an auditor reads invoices.
 */
@Component
public class ProductSkuAllocator {

    /**
     * Uncategorised, and the fallback for a category name with no Latin characters in it.
     *
     * <p>Deliberately not {@code GEN}. V110 argues that categories are nullable so a shop is not
     * pushed into inventing one bucket called "General" and putting everything in it — and a code
     * namespace full of {@code GEN-001} recreates exactly that, somewhere much harder to undo than
     * a category row. {@code P-0001} reads as "a product", which is all it is claiming.
     */
    static final String FALLBACK_PREFIX = "P";

    private static final int PREFIX_LENGTH = 3;

    /** {@code BEV-001}. Three digits is plenty per aisle, and 1000 simply widens it. */
    private static final String CATEGORY_FORMAT = "%s-%03d";

    /** {@code P-0001}. Wider, because everything uncategorised shares this one counter. */
    private static final String FALLBACK_FORMAT = "%s-%04d";

    private final JdbcTemplate jdbc;

    public ProductSkuAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The stem of a category's codes: {@code "Rice & Grains"} to {@code RIC}.
     *
     * <p>Everything outside {@code A-Z0-9} is dropped, which handles spaces and punctuation and
     * also means a Sinhala or Tamil name strips to nothing and falls back to {@link
     * #FALLBACK_PREFIX}. A shop naming its aisles in Sinhala therefore gets one shared counter and
     * a prefix that carries no hint. The codes stay unique and typeable, which is what the field is
     * for; giving each category an optional prefix of its own is the fix if that shop ever asks.
     *
     * <p>A name shorter than three characters is not padded — {@code AB} is honest, {@code AB0}
     * invents a character that will look like it means something.
     *
     * <p>This is mirrored by {@code skuPrefix} in {@code @lumora/domain}, which the form uses to
     * show the placeholder. The two are pinned by the same table of cases in their tests. Note the
     * duplication is deliberate and is <em>not</em> the money rule the package header states: a
     * drift here shows a wrong placeholder, never a wrong price.
     */
    static String prefixFor(String categoryName) {
        if (categoryName == null) {
            return FALLBACK_PREFIX;
        }
        // Locale.ROOT, not the default locale: on a Turkish-language Windows, "i".toUpperCase()
        // is "İ", and a till's product codes would depend on what language the PC was installed
        // in. The same reasoning as InvoiceNumberAllocator's QQQQ field.
        String stripped =
                categoryName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (stripped.isEmpty()) {
            return FALLBACK_PREFIX;
        }
        return stripped.length() <= PREFIX_LENGTH ? stripped : stripped.substring(0, PREFIX_LENGTH);
    }

    /**
     * The next code for a category, or for no category at all.
     *
     * @param categoryName the product's category, or null when it has none
     */
    String allocate(long tenantId, String categoryName) {
        String prefix = prefixFor(categoryName);

        // One atomic statement: two concurrent creates cannot be handed the same number. The
        // second blocks on the row lock the first holds, then re-reads and increments. There is no
        // range_end here, unlike invoice_counters, so unlike that allocator this can never come
        // back empty — a product code has no compliance bound to climb past.
        Long sequence =
                jdbc.queryForObject(
                        """
                        INSERT INTO product_sku_counters (tenant_id, prefix, next_seq)
                        VALUES (?, ?, 2)
                        ON CONFLICT (tenant_id, prefix) DO UPDATE
                            SET next_seq = product_sku_counters.next_seq + 1
                        RETURNING next_seq - 1
                        """,
                        Long.class,
                        tenantId,
                        prefix);

        String format = FALLBACK_PREFIX.equals(prefix) ? FALLBACK_FORMAT : CATEGORY_FORMAT;
        return format.formatted(prefix, sequence);
    }
}

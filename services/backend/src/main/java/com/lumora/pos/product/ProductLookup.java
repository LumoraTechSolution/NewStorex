package com.lumora.pos.product;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Finding a product, on a till, offline (M1-06).
 *
 * <p>There are two ways a product gets onto a sale and they have nothing in common.
 *
 * <p><strong>A scan</strong> is the overwhelming majority and it must be instantaneous and
 * unambiguous: one probe of a unique index, one product, straight onto the cart with no
 * decision for the cashier to make (M1-08). {@link #byBarcode} is that path and it never
 * touches the search machinery — a queue should not wait on a trigram scan.
 *
 * <p><strong>A search</strong> is the fallback for loose goods, a missing label, or a
 * product the cashier knows by name. It is forgiving and ranked, and the cashier picks from
 * a list.
 */
@Service
public class ProductLookup {

    /** Enough to fill the picker without turning it into a scrolling exercise. */
    private static final int DEFAULT_LIMIT = 20;

    private static final int MAX_LIMIT = 100;

    private static final String COLUMNS =
            """
            SELECT p.client_uuid, p.sku, p.name, p.price_minor, p.tax_mode, p.tax_rate_bp,
                   COALESCE(
                       array_agg(b.barcode ORDER BY b.is_primary DESC, b.barcode)
                           FILTER (WHERE b.barcode IS NOT NULL),
                       '{}') AS barcodes
            """;

    private final JdbcTemplate jdbc;

    public ProductLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every active product, for the back office and the M0 spike's picker. */
    public List<ProductSummary> active() {
        return jdbc.query(
                COLUMNS
                        + """
                        FROM products p
                        LEFT JOIN product_barcodes b ON b.product_id = p.id
                        WHERE p.active
                        GROUP BY p.id
                        ORDER BY p.name
                        """,
                MAPPER);
    }

    /**
     * The scanner path. One index probe on a unique key, so an ambiguous result is
     * impossible by construction rather than by tie-breaking.
     *
     * <p>Deliberately matches on the exact string. A barcode is machine-read; trimming or
     * case-folding it would only ever mask a data-entry problem in the catalogue, and
     * masking it means the wrong product is sold rather than none.
     */
    public Optional<ProductSummary> byBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        COLUMNS
                                + """
                                FROM products p
                                JOIN product_barcodes b ON b.product_id = p.id
                                WHERE p.active
                                  AND p.id = (SELECT product_id FROM product_barcodes WHERE barcode = ?)
                                GROUP BY p.id
                                """,
                        MAPPER,
                        barcode)
                .stream()
                .findFirst();
    }

    /**
     * Ranked search over barcode, SKU and name.
     *
     * <p>The ranking is the order a cashier would guess in: an exact code first, then an
     * exact SKU, then a name that starts with what was typed, then anything containing it.
     * Sorting by trigram similarity instead reads as arbitrary at the counter — "milk"
     * should put <em>Fresh Milk 1L</em> at the top, not whichever name happens to share the
     * most three-letter runs.
     */
    public List<ProductSummary> search(String query, Integer limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.trim();
        int capped = Math.min(limit == null || limit < 1 ? DEFAULT_LIMIT : limit, MAX_LIMIT);

        return jdbc.query(
                COLUMNS
                        + """
                        ,
                               CASE
                                 WHEN EXISTS (SELECT 1 FROM product_barcodes x
                                              WHERE x.product_id = p.id AND x.barcode = ?) THEN 0
                                 WHEN lower(p.sku) = lower(?) THEN 1
                                 WHEN lower(p.name) LIKE lower(?) || '%' THEN 2
                                 ELSE 3
                               END AS rank
                        FROM products p
                        LEFT JOIN product_barcodes b ON b.product_id = p.id
                        WHERE p.active AND (
                                 EXISTS (SELECT 1 FROM product_barcodes x
                                         WHERE x.product_id = p.id AND x.barcode = ?)
                              OR lower(p.sku) LIKE '%' || lower(?) || '%'
                              OR lower(p.name) LIKE '%' || lower(?) || '%'
                        )
                        GROUP BY p.id
                        ORDER BY rank, p.name
                        LIMIT ?
                        """,
                MAPPER,
                q,
                q,
                q,
                q,
                q,
                q,
                capped);
    }

    private static final RowMapper<ProductSummary> MAPPER =
            (ResultSet rs, int row) ->
                    new ProductSummary(
                            rs.getObject("client_uuid", UUID.class),
                            rs.getString("sku"),
                            rs.getString("name"),
                            rs.getLong("price_minor"),
                            rs.getString("tax_mode"),
                            rs.getInt("tax_rate_bp"),
                            readBarcodes(rs));

    private static List<String> readBarcodes(ResultSet rs) throws SQLException {
        Array array = rs.getArray("barcodes");
        if (array == null) {
            return List.of();
        }
        String[] values = (String[]) array.getArray();
        return values == null ? List.of() : Arrays.asList(values);
    }
}

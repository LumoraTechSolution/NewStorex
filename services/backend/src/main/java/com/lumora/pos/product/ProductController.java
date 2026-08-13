package com.lumora.pos.product;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalogue the terminal sells from.
 *
 * <p>Minimal on purpose — enough for the M0 spike to ring up a real product rather than a
 * hardcoded UUID that goes stale the moment the database is reseeded. Barcode lookup and
 * keyboard search arrive with M1-06.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final JdbcTemplate jdbc;

    public ProductController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<ProductSummary> active() {
        return jdbc.query(
                """
                SELECT client_uuid, sku, barcode, name, price_minor, tax_mode, tax_rate_bp
                FROM products
                WHERE active
                ORDER BY name
                """,
                (rs, row) ->
                        new ProductSummary(
                                rs.getObject("client_uuid", UUID.class),
                                rs.getString("sku"),
                                rs.getString("barcode"),
                                rs.getString("name"),
                                rs.getLong("price_minor"),
                                rs.getString("tax_mode"),
                                rs.getInt("tax_rate_bp")));
    }

    public record ProductSummary(
            UUID clientUuid,
            String sku,
            String barcode,
            String name,
            long priceMinor,
            String taxMode,
            int taxRateBp) {}
}

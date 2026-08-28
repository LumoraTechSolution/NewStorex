package com.lumora.pos.product;

import java.util.List;
import java.util.UUID;

/**
 * A product as the <em>back office</em> needs it (M3-02).
 *
 * <p>Deliberately not {@link ProductSummary}, which is what the till needs: enough to put a line on
 * a receipt and price it, and nothing more. This carries the database id, the category, and whether
 * the product is still sold — three things a cashier has no use for and an owner cannot edit
 * without.
 *
 * <p>Widening {@code ProductSummary} instead would put the id and the discontinued flag onto every
 * search result the till fetches on every keystroke, and would make the till's contract change
 * every time the back office grew a field. Two records, two readers.
 */
public record ProductRow(
        long id,
        UUID clientUuid,
        String sku,
        String name,
        long priceMinor,
        String taxMode,
        int taxRateBp,
        Long categoryId,
        String categoryName,
        List<String> barcodes,
        boolean active) {}

package com.lumora.pos.product;

import java.util.List;
import java.util.UUID;

/**
 * A product as the terminal needs it: enough to put a line on the screen and price it, and
 * nothing else.
 *
 * <p>Carries every barcode rather than one. A shopkeeper editing the catalogue needs to see
 * them all — the second code exists precisely because someone found goods that would not
 * scan — and the first in the list is the primary.
 */
public record ProductSummary(
        UUID clientUuid,
        String sku,
        String name,
        long priceMinor,
        String taxMode,
        int taxRateBp,
        List<String> barcodes) {

    /** The code to print or show when only one will fit. Null when the product has none. */
    public String primaryBarcode() {
        return barcodes.isEmpty() ? null : barcodes.get(0);
    }
}

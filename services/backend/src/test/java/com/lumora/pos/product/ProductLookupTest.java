package com.lumora.pos.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * M1-06 — finding a product on a till.
 *
 * <p>The scan path is tested harder than the search path because it is the one that runs on every
 * item of every sale, and because its failure mode is the expensive one: a scan that resolves to
 * the wrong product sells the wrong thing at the wrong price, and nobody notices until a stocktake.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
@Transactional
class ProductLookupTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProductLookup lookup;

    private long tenantId;

    @BeforeEach
    void seed() {
        tenantId =
                jdbc.queryForObject(
                        """
                        INSERT INTO tenants (client_uuid, name) VALUES (?, 'Test Shop') RETURNING id
                        """,
                        Long.class,
                        UUID.randomUUID());

        product("TEA-400", "Ceylon Tea 400g", 45000, "4791234567890");
        product("MILK-1L", "Fresh Milk 1L", 49000, "4791234567920");
        product("MILK-400G", "Milk Powder 400g", 139000, "4791234567937", "8901234567895");
        product("SUGAR-1KG", "White Sugar 1kg", 32000, "4791234567944");
    }

    private long product(String sku, String name, long priceMinor, String... barcodes) {
        long id =
                jdbc.queryForObject(
                        """
                        INSERT INTO products (client_uuid, tenant_id, sku, name, price_minor, tax_mode, tax_rate_bp)
                        VALUES (?, ?, ?, ?, ?, 'INCLUSIVE', 1800) RETURNING id
                        """,
                        Long.class,
                        UUID.randomUUID(),
                        tenantId,
                        sku,
                        name,
                        priceMinor);
        boolean primary = true;
        for (String barcode : barcodes) {
            jdbc.update(
                    """
                    INSERT INTO product_barcodes (client_uuid, tenant_id, product_id, barcode, is_primary)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    tenantId,
                    id,
                    barcode,
                    primary);
            primary = false;
        }
        return id;
    }

    // ------------------------------------------------------------------ the scan path

    @Test
    void aScanResolvesToExactlyOneProduct() {
        assertThat(lookup.byBarcode("4791234567890"))
                .hasValueSatisfying(p -> assertThat(p.name()).isEqualTo("Ceylon Tea 400g"));
    }

    @Test
    void everyBarcodeOnAProductScansToTheSameProduct() {
        // The whole reason barcodes moved out of a column: the second supplier code has to work.
        assertThat(lookup.byBarcode("4791234567937"))
                .hasValueSatisfying(p -> assertThat(p.name()).isEqualTo("Milk Powder 400g"));
        assertThat(lookup.byBarcode("8901234567895"))
                .hasValueSatisfying(p -> assertThat(p.name()).isEqualTo("Milk Powder 400g"));
    }

    @Test
    void anUnknownBarcodeIsEmptyRatherThanAGuess() {
        assertThat(lookup.byBarcode("0000000000000")).isEmpty();
        assertThat(lookup.byBarcode("")).isEmpty();
        assertThat(lookup.byBarcode(null)).isEmpty();
    }

    @Test
    void theSameBarcodeCannotBeGivenToTwoProducts() {
        // Enforced by the database, not by application code: an ambiguous scan must be
        // impossible to create, not merely handled when it appears.
        assertThatThrownBy(() -> product("TEA-200", "Ceylon Tea 200g", 25000, "4791234567890"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aProductCannotHaveTwoPrimaryBarcodes() {
        long id = product("RICE-5KG", "Samba Rice 5kg", 285000, "4791234567906");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO product_barcodes (client_uuid, tenant_id, product_id, barcode, is_primary)
                                        VALUES (?, ?, ?, '4791234567999', true)
                                        """,
                                        UUID.randomUUID(),
                                        tenantId,
                                        id))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void anInactiveProductDoesNotScan() {
        jdbc.update("UPDATE products SET active = false WHERE sku = 'TEA-400'");
        assertThat(lookup.byBarcode("4791234567890")).isEmpty();
    }

    @Test
    void theBarcodesComeBackPrimaryFirst() {
        assertThat(lookup.byBarcode("8901234567895"))
                .hasValueSatisfying(
                        p -> {
                            assertThat(p.barcodes()).containsExactly("4791234567937", "8901234567895");
                            assertThat(p.primaryBarcode()).isEqualTo("4791234567937");
                        });
    }

    // --------------------------------------------------------------- the search path

    @Test
    void nameSearchPutsThePrefixMatchFirst() {
        // "milk" matches both Fresh Milk 1L and Milk Powder 400g. The one that *starts*
        // with it wins, because that is the order a cashier expects to see.
        assertThat(lookup.search("milk", null))
                .extracting(ProductSummary::name)
                .containsExactly("Milk Powder 400g", "Fresh Milk 1L");
    }

    @Test
    void searchIsCaseInsensitiveAndMatchesMidWord() {
        assertThat(lookup.search("SUGAR", null)).extracting(ProductSummary::name).contains("White Sugar 1kg");
        assertThat(lookup.search("owder", null)).extracting(ProductSummary::name).contains("Milk Powder 400g");
    }

    @Test
    void anExactSkuOutranksANameThatMerelyContainsIt() {
        assertThat(lookup.search("MILK-1L", null))
                .extracting(ProductSummary::name)
                .startsWith("Fresh Milk 1L");
    }

    @Test
    void aBarcodeTypedIntoSearchStillFindsItsProduct() {
        assertThat(lookup.search("8901234567895", null))
                .extracting(ProductSummary::name)
                .containsExactly("Milk Powder 400g");
    }

    @Test
    void anEmptyQueryReturnsNothingRatherThanTheWholeCatalogue() {
        assertThat(lookup.search("", null)).isEmpty();
        assertThat(lookup.search("   ", null)).isEmpty();
        assertThat(lookup.search(null, null)).isEmpty();
    }

    @Test
    void searchExcludesInactiveProducts() {
        jdbc.update("UPDATE products SET active = false WHERE sku = 'MILK-1L'");
        assertThat(lookup.search("milk", null))
                .extracting(ProductSummary::name)
                .containsExactly("Milk Powder 400g");
    }

    @Test
    void theLimitIsHonouredAndCapped() {
        assertThat(lookup.search("m", 1)).hasSize(1);
        // A caller asking for a million rows gets the cap, not a stalled till.
        assertThat(lookup.search("m", 1_000_000)).hasSizeLessThanOrEqualTo(100);
    }

    @Test
    void activeListsEveryProductWithItsBarcodes() {
        assertThat(lookup.active()).hasSize(4);
        assertThat(lookup.active())
                .filteredOn(p -> p.sku().equals("MILK-400G"))
                .singleElement()
                .satisfies(p -> assertThat(p.barcodes()).hasSize(2));
    }
}

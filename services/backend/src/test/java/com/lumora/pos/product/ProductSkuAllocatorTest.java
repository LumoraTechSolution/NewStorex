package com.lumora.pos.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.lumora.pos.testfixtures.ShopFixture;
import com.lumora.pos.testfixtures.ShopFixture.Shop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Making up a product code (M3-02).
 *
 * <p>The prefix table below is a contract: {@code packages/domain/src/sku.test.ts} asserts the same
 * rows against the TypeScript twin the form uses for its placeholder. A case added on one side
 * belongs on the other — the two implementations are only worth having while they agree.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
@Transactional
class ProductSkuAllocatorTest {

    @Autowired ProductSkuAllocator allocator;
    @Autowired ShopFixture fixtures;

    // -------------------------------------------------------------------- the prefix

    @ParameterizedTest(name = "{0} gives {1} — {2}")
    @CsvSource({
        "Beverages,      BEV, the ordinary case",
        "Beverage,       BEV, collides with Beverages deliberately",
        "Tea,            TEA, exactly three characters",
        "Tea Bags,       TEA, the space goes before the cut so this collides with Tea",
        "Rice & Grains,  RIC, punctuation and spaces go",
        "3M Products,    3MP, digits are as good as letters",
        "AB,             AB,  shorter than three is not padded",
        "බීම,            P,   Sinhala strips to nothing",
        "பானங்கள்,          P,   Tamil strips to nothing",
        "日用品,          P,   any non-Latin script strips to nothing",
        "'!!!',          P,   punctuation alone strips to nothing",
    })
    void derivesThePrefixFromTheCategoryName(String categoryName, String expected, String why) {
        assertThat(ProductSkuAllocator.prefixFor(categoryName)).as(why).isEqualTo(expected);
    }

    @Test
    void readsNoCategoryAndABlankNameAsTheFallback() {
        assertThat(ProductSkuAllocator.prefixFor(null)).isEqualTo("P");
        assertThat(ProductSkuAllocator.prefixFor("")).isEqualTo("P");
        assertThat(ProductSkuAllocator.prefixFor("   ")).isEqualTo("P");
    }

    /**
     * Only fails on somebody else's machine: under a Turkish locale {@code "i".toUpperCase()} is
     * {@code "İ"}, which then strips out and turns ICE into CE.
     */
    @Test
    void uppercasesTheSameWayRegardlessOfTheMachinesLanguage() {
        assertThat(ProductSkuAllocator.prefixFor("iced tea")).isEqualTo("ICE");
        assertThat(ProductSkuAllocator.prefixFor("istanbul imports")).isEqualTo("IST");
    }

    // -------------------------------------------------------------------- the number

    @Test
    void theFirstCodeInACategoryIsOne() {
        Shop shop = fixtures.seed();
        assertThat(allocator.allocate(shop.tenantId(), "Beverages")).isEqualTo("BEV-001");
    }

    @Test
    void successiveCodesClimb() {
        Shop shop = fixtures.seed();

        assertThat(allocator.allocate(shop.tenantId(), "Stationery")).isEqualTo("STA-001");
        assertThat(allocator.allocate(shop.tenantId(), "Stationery")).isEqualTo("STA-002");
        assertThat(allocator.allocate(shop.tenantId(), "Stationery")).isEqualTo("STA-003");
    }

    /**
     * Two categories sharing a prefix share the counter. That is what keeps the codes unique when
     * the prefix cannot tell them apart — the alternative, disambiguating to BEV2, would make
     * renaming a category change the prefix of everything added afterwards.
     */
    @Test
    void categoriesThatDeriveTheSamePrefixShareOneCounter() {
        Shop shop = fixtures.seed();

        assertThat(allocator.allocate(shop.tenantId(), "Confectionery")).isEqualTo("CON-001");
        assertThat(allocator.allocate(shop.tenantId(), "Condiments")).isEqualTo("CON-002");
    }

    @Test
    void anUncategorisedProductCountsOnTheFallbackAndIsPaddedWider() {
        Shop shop = fixtures.seed();

        // Four digits, not three: everything a shop never categorised shares this one counter, so
        // it climbs faster than any single aisle.
        assertThat(allocator.allocate(shop.tenantId(), null)).isEqualTo("P-0001");
        assertThat(allocator.allocate(shop.tenantId(), null)).isEqualTo("P-0002");
    }

    @Test
    void aSinhalaNamedCategoryFallsInWithTheUncategorised() {
        Shop shop = fixtures.seed();

        assertThat(allocator.allocate(shop.tenantId(), "බීම")).isEqualTo("P-0001");
        assertThat(allocator.allocate(shop.tenantId(), null)).isEqualTo("P-0002");
    }
}

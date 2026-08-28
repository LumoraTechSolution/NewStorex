package com.lumora.pos.product;

import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalogue the terminal sells from (M1-06).
 *
 * <p>Loopback only, like everything on the desktop profile. There is no authentication here
 * yet — M3-08 introduces PINs — and the till's API is not reachable from the LAN by design.
 */
/*
 * Desktop profile only.
 *
 * <p>Without this the class is a bean under every profile, so the cloud instance mounted it too —
 * behind M4-01's filter, but mounted. Everything it calls goes through {@code LocalShop}, which
 * asserts the database holds exactly one tenant, so on the cloud it could only ever fail. A route
 * that exists and always fails is worse than one that does not exist: it is a promise in the URL
 * space that somebody eventually tries to keep.
 */
@RestController
@RequestMapping("/api/products")
@Profile("desktop")
public class ProductController {

    private final ProductLookup lookup;

    public ProductController(ProductLookup lookup) {
        this.lookup = lookup;
    }

    @GetMapping
    public List<ProductSummary> active() {
        return lookup.active();
    }

    /**
     * One endpoint for both the gun and the keyboard.
     *
     * <p>{@code exactMatch} is the part that matters to the caller: true means the query was
     * a barcode and resolved to exactly one product, so the terminal adds it and moves on
     * without showing anything. False means these are candidates and the cashier chooses.
     * Without that flag the UI would have to guess from the result count, and a search that
     * happened to return one row would silently behave like a scan.
     */
    @GetMapping("/search")
    public ProductSearchResult search(
            @RequestParam("q") String query, @RequestParam(value = "limit", required = false) Integer limit) {

        Optional<ProductSummary> scanned = lookup.byBarcode(query == null ? null : query.trim());
        if (scanned.isPresent()) {
            return new ProductSearchResult(true, List.of(scanned.get()));
        }
        return new ProductSearchResult(false, lookup.search(query, limit));
    }

    public record ProductSearchResult(boolean exactMatch, List<ProductSummary> products) {}
}

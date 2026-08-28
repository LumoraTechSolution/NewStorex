package com.lumora.pos.product;

import com.lumora.pos.product.ProductAdminService.ProductDraft;
import com.lumora.pos.product.ProductImportService.ImportPlan;
import com.lumora.pos.product.ProductImportService.ImportRow;
import com.lumora.pos.shop.LocalShop;
import com.lumora.pos.web.RejectedException;
import com.lumora.pos.auth.SessionService;
import com.lumora.pos.user.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalogue as the back office edits it (M3-02).
 *
 * <p>Mounted under {@code /api/back-office} rather than alongside {@link ProductController}'s
 * {@code /api/products}, and the split is the point: those two endpoints are read by different
 * software for different reasons. The till's reads are unauthenticated because a scan cannot wait
 * for a PIN; everything here demands {@link Permission#MANAGE_PRODUCTS} on every single call. Two
 * verbs on one path, one of them gated and one not, is the kind of arrangement where a later GET
 * gets added to the wrong half.
 *
 * <p>Reading the catalogue needs only {@link Permission#BACK_OFFICE}, so a manager can look up what
 * something costs without holding the permission to change it.
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
@RequestMapping("/api/back-office")
@Profile("desktop")
public class ProductAdminController {

    private final ProductAdminService products;
    private final ProductImportService imports;
    private final SessionService sessions;
    private final LocalShop shop;

    public ProductAdminController(
            ProductAdminService products,
            ProductImportService imports,
            SessionService sessions,
            LocalShop shop) {
        this.products = products;
        this.imports = imports;
        this.sessions = sessions;
        this.shop = shop;
    }

    // ------------------------------------------------------------------------- products

    @GetMapping("/products")
    public List<ProductRow> list(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer) {
        sessions.require(bearer, Permission.BACK_OFFICE);
        return products.list(shop.soleTenantId());
    }

    @PostMapping("/products")
    public ProductRow create(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @Valid @RequestBody ProductRequest request) {
        sessions.require(bearer, Permission.MANAGE_PRODUCTS);
        return products.create(shop.soleTenantId(), request.toDraft());
    }

    @PutMapping("/products/{productId}")
    public ProductRow save(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @PathVariable long productId,
            @Valid @RequestBody ProductRequest request) {
        sessions.require(bearer, Permission.MANAGE_PRODUCTS);
        return products.save(shop.soleTenantId(), productId, request.toDraft());
    }

    /** Discontinues or reinstates. There is no DELETE — a sold product has to stay resolvable. */
    @PutMapping("/products/{productId}/active")
    public ProductRow setActive(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @PathVariable long productId,
            @Valid @RequestBody SetActiveRequest request) {
        sessions.require(bearer, Permission.MANAGE_PRODUCTS);
        return products.setActive(shop.soleTenantId(), productId, request.active());
    }

    // --------------------------------------------------------------------------- import

    /**
     * What the file would do, without doing any of it (M3-03).
     *
     * <p>A POST, despite changing nothing. The rows do not fit in a query string — a four-hundred
     * line catalogue is not a URL — and a GET with a body is the kind of thing an intermediary is
     * entitled to drop.
     *
     * <p>Gated on {@link Permission#MANAGE_PRODUCTS} rather than the weaker read permission,
     * because a preview is the first half of a write and the two should not have different
     * answers about who is allowed.
     */
    @PostMapping("/products/import/preview")
    public ImportPlan preview(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @Valid @RequestBody ImportRequest request) {
        sessions.require(bearer, Permission.MANAGE_PRODUCTS);
        return imports.plan(shop.soleTenantId(), request.rows());
    }

    /**
     * Applies a plan the caller has already been shown.
     *
     * <p>{@code planHash} comes from the preview and is checked against a freshly computed plan, so
     * a file swapped between the two screens — or a price changed at the till in between — is
     * refused rather than silently applied.
     */
    @PostMapping("/products/import")
    public ImportPlan importProducts(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @Valid @RequestBody ImportRequest request) {
        sessions.require(bearer, Permission.MANAGE_PRODUCTS);
        if (request.planHash() == null || request.planHash().isBlank()) {
            throw new RejectedException("Preview this import before running it.");
        }
        return imports.apply(shop.soleTenantId(), request.rows(), request.planHash());
    }

    // ----------------------------------------------------------------------- categories

    @GetMapping("/categories")
    public List<CategoryRow> categories(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer) {
        sessions.require(bearer, Permission.BACK_OFFICE);
        return products.categories(shop.soleTenantId());
    }

    @PostMapping("/categories")
    public CategoryRow createCategory(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @Valid @RequestBody CreateCategoryRequest request) {
        sessions.require(bearer, Permission.MANAGE_PRODUCTS);
        return products.createCategory(shop.soleTenantId(), request.clientUuid(), request.name());
    }

    @PutMapping("/categories/{categoryId}")
    public CategoryRow updateCategory(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @PathVariable long categoryId,
            @Valid @RequestBody UpdateCategoryRequest request) {
        sessions.require(bearer, Permission.MANAGE_PRODUCTS);
        return products.updateCategory(
                shop.soleTenantId(), categoryId, request.name(), request.active());
    }

    // ------------------------------------------------------------------------- payloads

    /**
     * The same body for a create and an edit.
     *
     * <p>{@code clientUuid} is ignored on an edit — the product already has one — and required on a
     * create. Making it optional here rather than splitting the record keeps one place where the
     * shape of a product is written down.
     *
     * <p>{@code priceMinor} is integer minor units and arrives that way. The screen parses what the
     * owner typed with {@code parseAmountToMinor} from {@code @lumora/domain}; a decimal crossing
     * this boundary would be a float somewhere, and 0.29 × 100 is 28.999999999999996.
     */
    public record ProductRequest(
            UUID clientUuid,
            @NotBlank String sku,
            @NotBlank String name,
            long priceMinor,
            @NotBlank String taxMode,
            int taxRateBp,
            Long categoryId,
            List<String> barcodes) {

        ProductDraft toDraft() {
            return new ProductDraft(
                    clientUuid == null ? UUID.randomUUID() : clientUuid,
                    sku,
                    name,
                    priceMinor,
                    taxMode,
                    taxRateBp,
                    categoryId,
                    barcodes == null ? List.of() : barcodes);
        }
    }

    public record SetActiveRequest(boolean active) {}

    /**
     * Rows, not a CSV.
     *
     * <p>The file is parsed by {@code parseProductCsv} in {@code @lumora/domain}, because a CSV
     * holds prices as text and turning text into minor units is money math — which §A puts in
     * exactly one package. A Java parser here would be the second implementation of a decimal
     * shift, and the two would eventually disagree by a cent on a receipt.
     *
     * <p>{@code planHash} is absent on a preview and required on an import.
     */
    public record ImportRequest(@NotNull List<ImportRow> rows, String planHash) {}

    public record CreateCategoryRequest(@NotNull UUID clientUuid, @NotBlank String name) {}

    public record UpdateCategoryRequest(@NotBlank String name, boolean active) {}
}

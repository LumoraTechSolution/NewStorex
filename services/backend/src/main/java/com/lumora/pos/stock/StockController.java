package com.lumora.pos.stock;

import com.lumora.pos.shop.LocalShop;
import com.lumora.pos.stock.GoodsReceiptService.ReceiptLine;
import com.lumora.pos.stock.GoodsReceiptService.ReceiptRow;
import com.lumora.pos.stock.StockAdjustmentService.AdjustmentRow;
import com.lumora.pos.stock.StockOnHandService.OnHandRow;
import com.lumora.pos.stock.StockOnHandService.OnHandSummary;
import com.lumora.pos.stock.StocktakeService.StocktakeRow;
import com.lumora.pos.stock.SupplierService.SupplierRow;
import com.lumora.pos.auth.SessionService;
import com.lumora.pos.user.Permission;
import com.lumora.pos.user.UserService.Operator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything that moves stock outside a sale: suppliers and deliveries (M3-04), and adjustments
 * (M3-05).
 *
 * <p>Everything here is behind {@link Permission#MANAGE_STOCK}, including the reads. That is a
 * deliberate difference from the products screen, where reading needs only {@code BACK_OFFICE}: a
 * goods receipt carries what the shop <em>paid</em>, and cost prices are the one thing on a till
 * that an owner may reasonably not want every manager reading.
 *
 * <p>There is no PUT and no DELETE anywhere in this controller. A receipt is a document (see {@link
 * GoodsReceiptService}) and an adjustment is a movement; neither is edited, and the way to correct
 * either is another adjustment, which leaves the mistake and the fix both on the record.
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
public class StockController {

    private final SupplierService suppliers;
    private final GoodsReceiptService receipts;
    private final StockAdjustmentService adjustments;
    private final StocktakeService stocktakes;
    private final StockOnHandService onHandService;
    private final SessionService sessions;
    private final LocalShop shop;

    public StockController(
            SupplierService suppliers,
            GoodsReceiptService receipts,
            StockAdjustmentService adjustments,
            StocktakeService stocktakes,
            StockOnHandService onHandService,
            SessionService sessions,
            LocalShop shop) {
        this.suppliers = suppliers;
        this.receipts = receipts;
        this.adjustments = adjustments;
        this.stocktakes = stocktakes;
        this.onHandService = onHandService;
        this.sessions = sessions;
        this.shop = shop;
    }

    // ------------------------------------------------------------------------ suppliers

    @GetMapping("/suppliers")
    public List<SupplierRow> suppliers(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer) {
        sessions.require(bearer, Permission.MANAGE_STOCK);
        return suppliers.list(shop.soleTenantId());
    }

    @PostMapping("/suppliers")
    public SupplierRow createSupplier(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @Valid @RequestBody CreateSupplierRequest request) {
        sessions.require(bearer, Permission.MANAGE_STOCK);
        return suppliers.create(
                shop.soleTenantId(), request.clientUuid(), request.name(), request.contact());
    }

    @PutMapping("/suppliers/{supplierId}")
    public SupplierRow updateSupplier(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @PathVariable long supplierId,
            @Valid @RequestBody UpdateSupplierRequest request) {
        sessions.require(bearer, Permission.MANAGE_STOCK);
        return suppliers.update(
                shop.soleTenantId(), supplierId, request.name(), request.contact(), request.active());
    }

    // ------------------------------------------------------------------ goods receipts

    @GetMapping("/goods-receipts")
    public List<ReceiptRow> recentReceipts(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @RequestParam(value = "limit", required = false) Integer limit) {
        sessions.require(bearer, Permission.MANAGE_STOCK);
        return receipts.recent(shop.soleTenantId(), limit == null ? 25 : limit);
    }

    @GetMapping("/goods-receipts/{receiptId}")
    public ReceiptRow receipt(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @PathVariable long receiptId) {
        sessions.require(bearer, Permission.MANAGE_STOCK);
        return receipts.byId(shop.soleTenantId(), receiptId);
    }

    /**
     * Books a delivery in.
     *
     * <p>The operator comes back out of the gate rather than being looked up again, so the person
     * recorded on {@code created_by} is provably the one whose PIN passed — the same reasoning that
     * shaped {@code UserService.authorise} returning the operator instead of a boolean.
     */
    @PostMapping("/goods-receipts")
    public ReceiptRow receive(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @Valid @RequestBody ReceiveRequest request) {
        Operator operator = sessions.require(bearer, Permission.MANAGE_STOCK);
        return receipts.receive(
                shop.soleTenantId(),
                request.branchCode(),
                request.clientUuid(),
                request.supplierId(),
                request.reference(),
                request.note(),
                request.lines().stream()
                        .map(line -> new ReceiptLine(line.productClientUuid(), line.qty(), line.unitCostMinor()))
                        .toList(),
                operator);
    }

    // --------------------------------------------------------------------- adjustments

    /**
     * Moves stock with no document behind it (M3-05).
     *
     * <p>{@code qtyDelta} arrives already signed. The screen builds the sign from the reason with
     * {@code signedAdjustmentQty}, and {@link AdjustmentReason#requireConsistent} re-checks it here
     * — a client that sends {@code DAMAGED, +5} is refused rather than quietly putting stock on a
     * shelf that is actually empty.
     */
    @PostMapping("/stock-adjustments")
    public AdjustmentRow adjust(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @Valid @RequestBody AdjustRequest request) {
        Operator operator = sessions.require(bearer, Permission.MANAGE_STOCK);
        return adjustments.adjust(
                shop.soleTenantId(),
                request.branchCode(),
                request.clientUuid(),
                request.productClientUuid(),
                request.qtyDelta(),
                request.reasonCode(),
                request.note(),
                operator);
    }

    @GetMapping("/stock-adjustments")
    public List<AdjustmentRow> recentAdjustments(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @RequestParam(value = "limit", required = false) Integer limit) {
        sessions.require(bearer, Permission.MANAGE_STOCK);
        return adjustments.recent(shop.soleTenantId(), limit == null ? 25 : limit);
    }

    /** What the shelf holds now, so the form can show the consequence before and after. */
    @GetMapping("/stock-on-hand")
    public OnHandResponse onHand(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @RequestParam("branchCode") String branchCode,
            @RequestParam("productClientUuid") UUID productClientUuid) {
        sessions.require(bearer, Permission.MANAGE_STOCK);
        long tenantId = shop.soleTenantId();
        return new OnHandResponse(
                productClientUuid,
                adjustments.onHand(tenantId, shop.branch(branchCode).id(), productClientUuid));
    }

    // ------------------------------------------------------------------------- on hand

    /**
     * What is on every shelf (M3-07).
     *
     * <p>Includes products that have never moved, as zero. The view says nothing about them —
     * correctly, since nothing happened — and turning that into a zero is presentation.
     */
    @GetMapping("/stock-on-hand/all")
    public OnHandListResponse allOnHand(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @RequestParam("branchCode") String branchCode,
            @RequestParam(value = "includeDiscontinued", required = false) Boolean includeDiscontinued) {
        sessions.require(bearer, Permission.MANAGE_STOCK);
        long tenantId = shop.soleTenantId();
        List<OnHandRow> rows =
                onHandService.all(
                        tenantId,
                        shop.branch(branchCode).id(),
                        Boolean.TRUE.equals(includeDiscontinued));
        return new OnHandListResponse(rows, onHandService.summarise(rows));
    }

    // ---------------------------------------------------------------------- stocktakes

    /**
     * The count currently open at this branch, if there is one (M3-06).
     *
     * <p>Returns 200 with a null body rather than a 404 when nothing is open. "No stocktake is
     * running" is an ordinary answer to an ordinary question, and a screen that has to catch an
     * error to learn it is a screen where the error path is the common path.
     */
    @GetMapping("/stocktakes/current")
    public StocktakeRow currentStocktake(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @RequestParam("branchCode") String branchCode) {
        sessions.require(bearer, Permission.MANAGE_STOCK);
        return stocktakes.current(shop.soleTenantId(), branchCode).orElse(null);
    }

    @GetMapping("/stocktakes")
    public List<StocktakeRow> recentStocktakes(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @RequestParam(value = "limit", required = false) Integer limit) {
        sessions.require(bearer, Permission.MANAGE_STOCK);
        return stocktakes.recent(shop.soleTenantId(), limit == null ? 10 : limit);
    }

    @PostMapping("/stocktakes")
    public StocktakeRow openStocktake(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @Valid @RequestBody OpenStocktakeRequest request) {
        Operator operator = sessions.require(bearer, Permission.MANAGE_STOCK);
        return stocktakes.open(
                shop.soleTenantId(),
                request.branchCode(),
                request.clientUuid(),
                request.note(),
                operator);
    }

    /** Records what was on the shelf. Counting the same product again replaces the line. */
    @PutMapping("/stocktakes/{stocktakeId}/counts")
    public StocktakeRow countStock(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @PathVariable long stocktakeId,
            @Valid @RequestBody CountRequest request) {
        Operator operator = sessions.require(bearer, Permission.MANAGE_STOCK);
        return stocktakes.count(
                shop.soleTenantId(),
                stocktakeId,
                request.productClientUuid(),
                request.countedQty(),
                operator);
    }

    @DeleteMapping("/stocktakes/{stocktakeId}/counts/{productClientUuid}")
    public StocktakeRow uncountStock(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @PathVariable long stocktakeId,
            @PathVariable UUID productClientUuid) {
        sessions.require(bearer, Permission.MANAGE_STOCK);
        return stocktakes.uncount(shop.soleTenantId(), stocktakeId, productClientUuid);
    }

    /** Writes the variances as STOCKTAKE movements and closes the count. */
    @PostMapping("/stocktakes/{stocktakeId}/complete")
    public StocktakeRow completeStocktake(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @PathVariable long stocktakeId) {
        Operator operator = sessions.require(bearer, Permission.MANAGE_STOCK);
        return stocktakes.complete(shop.soleTenantId(), stocktakeId, operator);
    }

    @PostMapping("/stocktakes/{stocktakeId}/abandon")
    public StocktakeRow abandonStocktake(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String bearer,
            @PathVariable long stocktakeId) {
        Operator operator = sessions.require(bearer, Permission.MANAGE_STOCK);
        return stocktakes.abandon(shop.soleTenantId(), stocktakeId, operator);
    }

    // ------------------------------------------------------------------------- payloads

    public record CreateSupplierRequest(
            @NotNull UUID clientUuid, @NotBlank String name, String contact) {}

    public record UpdateSupplierRequest(@NotBlank String name, String contact, boolean active) {}

    /**
     * {@code unitCostMinor} is integer minor units and arrives that way, like every other amount
     * crossing this boundary. The screen parses what was typed with {@code parseAmountToMinor} from
     * {@code @lumora/domain}; a decimal here would be a float somewhere.
     */
    public record ReceiveLineRequest(
            @NotNull UUID productClientUuid, int qty, long unitCostMinor) {}

    public record AdjustRequest(
            @NotNull UUID clientUuid,
            @NotBlank String branchCode,
            @NotNull UUID productClientUuid,
            int qtyDelta,
            @NotBlank String reasonCode,
            String note) {}

    public record OnHandResponse(UUID productClientUuid, int onHand) {}

    public record OnHandListResponse(List<OnHandRow> rows, OnHandSummary summary) {}

    public record OpenStocktakeRequest(
            @NotNull UUID clientUuid, @NotBlank String branchCode, String note) {}

    public record CountRequest(@NotNull UUID productClientUuid, int countedQty) {}

    public record ReceiveRequest(
            @NotNull UUID clientUuid,
            @NotBlank String branchCode,
            long supplierId,
            String reference,
            String note,
            @NotEmpty List<@Valid ReceiveLineRequest> lines) {}
}

package com.lumora.pos.cloud;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's read API (M4-05 … M4-07).
 *
 * <p>Every method resolves its tenant the same way and cannot do otherwise: {@link CloudPrincipals}
 * takes it from the session the filter authenticated, so there is no parameter a caller could set to
 * read somebody else's shop.
 *
 * <p><b>Console credentials only.</b> A till's token is refused here with a 403 — see
 * {@link AuthenticatedPrincipal} on why the two kinds are kept apart.
 */
@RestController
@RequestMapping("/api/console")
@Profile("cloud")
public class ConsoleReportController {

    /**
     * Sri Lanka has one zone and no daylight saving, so this default is correct for every shop v1
     * will ever see. It is a parameter rather than a constant because the alternative is finding out
     * at the first overseas tenant that "today" is baked into six SQL statements.
     */
    private static final String DEFAULT_ZONE = "Asia/Colombo";

    private final ConsoleReportService reports;
    private final ConsoleAttentionService attention;
    private final ConsoleStockService stock;

    public ConsoleReportController(
            ConsoleReportService reports,
            ConsoleAttentionService attention,
            ConsoleStockService stock) {
        this.reports = reports;
        this.attention = attention;
        this.stock = stock;
    }

    @GetMapping("/today")
    public ConsoleReportService.Today today(
            HttpServletRequest request,
            @RequestParam(defaultValue = DEFAULT_ZONE) String zone) {
        return reports.today(tenantId(request), zone);
    }

    @GetMapping("/trend")
    public List<ConsoleReportService.DailyTotal> trend(
            HttpServletRequest request,
            @RequestParam(defaultValue = "14") int days,
            @RequestParam(defaultValue = DEFAULT_ZONE) String zone) {
        return reports.dailyTotals(tenantId(request), zone, days);
    }

    @GetMapping("/branches")
    public List<ConsoleReportService.BranchTotal> branches(
            HttpServletRequest request,
            @RequestParam(defaultValue = DEFAULT_ZONE) String zone) {
        return reports.branchTotals(tenantId(request), zone);
    }

    /**
     * @param thresholdMinor LKR 100.00 by default, matching the till's own D1 default. The shop's
     *     configured threshold lives in the desktop database and does not sync, so the console
     *     cannot read it yet — the parameter is how a caller overrides in the meantime.
     */
    /**
     * @param reviewed false — the default — lists what still needs looking at. True lists what has
     *     already been cleared, which is the half that makes the button safe to press: an alert you
     *     can dismiss and never find again is a different kind of useless (M6-10).
     */
    @GetMapping("/attention")
    public List<ConsoleReportService.CashVariance> attention(
            HttpServletRequest request,
            @RequestParam(defaultValue = "14") int days,
            @RequestParam(defaultValue = "10000") long thresholdMinor,
            @RequestParam(defaultValue = "false") boolean reviewed) {
        return reports.cashVariances(tenantId(request), days, thresholdMinor, reviewed);
    }

    /** One variance in full, including the denominations behind the count (M6-10). */
    @GetMapping("/attention/{shiftClientUuid}")
    public ConsoleAttentionService.VarianceDetail variance(
            HttpServletRequest request, @PathVariable UUID shiftClientUuid) {
        return attention.detail(tenantId(request), shiftClientUuid);
    }

    /**
     * Records that somebody looked at a variance (M6-10).
     *
     * <p><b>The console's only write.</b> Everything else on this controller reads, and
     * {@link AuthenticatedPrincipal} explains why the two credential kinds are kept apart at all.
     * This one is allowed because it touches no money and no ledger: it cannot change what a shift
     * says, it is attributed to the session that did it, and the shift stays listed under
     * {@code ?reviewed=true} afterwards. A stolen session can hide an alert; it cannot hide a shift.
     *
     * <p>The author comes from the session and never from the body. Who cleared a cash variance is
     * the only thing this row is for, and a client-supplied answer to that is worth nothing.
     */
    @PostMapping("/attention/{shiftClientUuid}/acknowledge")
    public ConsoleAttentionService.VarianceDetail acknowledge(
            HttpServletRequest request,
            @PathVariable UUID shiftClientUuid,
            @RequestBody(required = false) AcknowledgeRequest body) {
        AuthenticatedPrincipal principal =
                CloudPrincipals.require(request, AuthenticatedPrincipal.Kind.CONSOLE);
        return attention.acknowledge(
                principal.tenantId(),
                shiftClientUuid,
                principal.credentialId(),
                body == null ? null : body.note());
    }

    /** @param note what the owner concluded. Optional — most of the time it is "I checked". */
    public record AcknowledgeRequest(String note) {}

    /**
     * What the shop is about to run out of (M6-12).
     *
     * <p>The one stock question that is about tomorrow rather than about the past, and the reason
     * this screen exists at all: an owner away from the shop could see the money and not the goods.
     */
    @GetMapping("/stock/low")
    public List<ConsoleStockService.StockLine> lowStock(
            HttpServletRequest request, @RequestParam(defaultValue = "50") int limit) {
        return stock.lowStock(tenantId(request), limit);
    }

    /** On hand for everything the shop sells, or for a piece of a name or SKU (M6-12). */
    @GetMapping("/stock")
    public List<ConsoleStockService.StockLine> onHand(
            HttpServletRequest request,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "100") int limit) {
        return stock.onHand(tenantId(request), q, limit);
    }

    /**
     * Who was on the till, and what they took (M6-13).
     *
     * @param day the shop's own day, defaulting to today in {@code zone}. A date rather than an
     *     offset, because "the 30th" is what somebody is holding a piece of paper about.
     */
    @GetMapping("/operators")
    public List<ConsoleReportService.OperatorDay> operators(
            HttpServletRequest request,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(defaultValue = DEFAULT_ZONE) String zone) {
        LocalDate asked = day == null ? LocalDate.now(java.time.ZoneId.of(zone)) : day;
        return reports.operatorDay(tenantId(request), asked, zone);
    }

    /**
     * The shape of the day, against the shape of a normal one (M6-14).
     *
     * <p>Ninety-six quarter hours, each carrying what the shop did and what it usually does. The
     * console draws them as two facing profiles; an owner reads density rather than an axis.
     */
    @GetMapping("/pulse")
    public List<ConsoleReportService.PulseSlot> pulse(
            HttpServletRequest request,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(defaultValue = DEFAULT_ZONE) String zone) {
        LocalDate asked = day == null ? LocalDate.now(java.time.ZoneId.of(zone)) : day;
        return reports.pulse(tenantId(request), asked, zone);
    }

    @GetMapping("/recent-sales")
    public List<ConsoleReportService.RecentSale> recentSales(
            HttpServletRequest request, @RequestParam(defaultValue = "20") int limit) {
        return reports.recentSales(tenantId(request), limit);
    }

    private static long tenantId(HttpServletRequest request) {
        return CloudPrincipals.require(request, AuthenticatedPrincipal.Kind.CONSOLE).tenantId();
    }
}

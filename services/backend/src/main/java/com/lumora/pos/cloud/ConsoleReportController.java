package com.lumora.pos.cloud;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
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

    public ConsoleReportController(ConsoleReportService reports) {
        this.reports = reports;
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
    @GetMapping("/attention")
    public List<ConsoleReportService.CashVariance> attention(
            HttpServletRequest request,
            @RequestParam(defaultValue = "14") int days,
            @RequestParam(defaultValue = "10000") long thresholdMinor) {
        return reports.cashVariances(tenantId(request), days, thresholdMinor);
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

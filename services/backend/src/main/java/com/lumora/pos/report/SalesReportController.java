package com.lumora.pos.report;

import com.lumora.pos.auth.SessionService;
import com.lumora.pos.report.SalesReportService.ClosedShift;
import com.lumora.pos.report.SalesReportService.DaySales;
import com.lumora.pos.report.SalesReportService.TopProduct;
import com.lumora.pos.shop.LocalShop;
import com.lumora.pos.user.Permission;
import com.lumora.pos.web.RejectedException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/**
 * The back office's reports (M3-10).
 *
 * <h2>Gated, where the Z-report is not</h2>
 *
 * Every endpoint here needs {@link Permission#BACK_OFFICE}. {@code ZReportController} deliberately
 * does not: the till prints a Z-report at the moment a shift closes, from a screen a cashier is
 * standing at, and putting a back-office session in front of that would mean a cashier cannot close
 * their own drawer. These are the opposite case — somebody sitting down to read the shop's takings
 * — and the takings are exactly what a shop does not want visible to whoever walks past the till.
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
@RequestMapping("/api/reports")
@Profile("desktop")
public class SalesReportController {

    /**
     * The most rows any of these will return.
     *
     * <p>A cap rather than paging. These are screens somebody reads, not a data export: a shopkeeper
     * scanning their best sellers wants twenty rows and will never scroll to four hundred, and page
     * controls on a list nobody pages is UI to maintain for nothing. When an export is wanted it
     * will be a different endpoint with a different shape (M5-10).
     */
    private static final int MAX_ROWS = 200;

    private final SalesReportService reports;
    private final SessionService sessions;
    private final LocalShop shop;

    public SalesReportController(
            SalesReportService reports, SessionService sessions, LocalShop shop) {
        this.reports = reports;
        this.sessions = sessions;
        this.shop = shop;
    }

    /** Defaults to today, which is what the screen opens on and what is asked for nine times in ten. */
    @GetMapping("/day")
    public DaySales day(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        sessions.require(bearer, Permission.BACK_OFFICE);
        return reports.day(shop.soleTenantId(), date == null ? LocalDate.now() : date);
    }

    @GetMapping("/top-products")
    public List<TopProduct> topProducts(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(defaultValue = "20") int limit) {
        sessions.require(bearer, Permission.BACK_OFFICE);

        LocalDate end = to == null ? LocalDate.now() : to;
        // Four weeks: long enough that a slow-moving line still shows up, short enough that the
        // answer is about what sells now rather than what sold at Christmas.
        LocalDate start = from == null ? end.minusDays(27) : from;
        if (start.isAfter(end)) {
            throw new RejectedException("The start of the range is after its end.");
        }
        return reports.topProducts(shop.soleTenantId(), start, end, clamp(limit));
    }

    /**
     * The shift history, a window at a time (M6-11).
     *
     * <p>No parameters at all still means "the newest thirty", which is what every existing caller
     * asks for and what the screen opens on. {@code from}/{@code to} narrow it to a range somebody
     * named; {@code beforeClosedAt} and {@code beforeId} continue after the last row they have.
     *
     * <p>The two are usable together, and that combination is the one that matters: "last week" is
     * often more than thirty shifts on a busy multi-till shop, and a range that silently truncated
     * would be worse than no range at all.
     */
    @GetMapping("/shifts")
    public List<ClosedShift> closedShifts(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Instant beforeClosedAt,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "30") int limit) {
        sessions.require(bearer, Permission.BACK_OFFICE);
        if (from != null && to != null && from.isAfter(to)) {
            throw new RejectedException("The start of the range is after its end.");
        }
        return reports.closedShifts(
                shop.soleTenantId(), from, to, beforeClosedAt, beforeId, clamp(limit));
    }

    /**
     * Clamps rather than refuses.
     *
     * <p>A limit of zero or a limit of a million are both a caller getting it wrong, and neither is
     * worth an error a person has to read: the first would show an empty report that looks like a
     * shop with no sales, and the second is a query nobody meant to run.
     */
    private static int clamp(int limit) {
        return Math.max(1, Math.min(MAX_ROWS, limit));
    }
}

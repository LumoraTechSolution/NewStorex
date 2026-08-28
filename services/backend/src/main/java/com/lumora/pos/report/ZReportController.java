package com.lumora.pos.report;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/** The Z-report (M2-11). Local, and closed shifts only — see {@link ZReportService}. */
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
public class ZReportController {

    private final ZReportService reports;

    public ZReportController(ZReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/z/{shiftId}")
    public ZReport zReport(@PathVariable long shiftId) {
        return reports.forShift(shiftId);
    }
}

package com.lumora.pos.report;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Z-report (M2-11). Local, and closed shifts only — see {@link ZReportService}. */
@RestController
@RequestMapping("/api/reports")
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

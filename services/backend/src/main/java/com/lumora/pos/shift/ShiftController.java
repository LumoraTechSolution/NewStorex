package com.lumora.pos.shift;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/** The shift lifecycle (M2-01 … M2-04). */
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
@RequestMapping("/api/shifts")
@Profile("desktop")
public class ShiftController {

    private final ShiftService shifts;

    public ShiftController(ShiftService shifts) {
        this.shifts = shifts;
    }

    @PostMapping
    public ShiftResponse open(@Valid @RequestBody OpenShiftRequest request) {
        return shifts.open(request);
    }

    /**
     * What the till shows in its status strip. Polled while trading, and deliberately unable to
     * carry the expected drawer total — see {@link ShiftStatusResponse}.
     */
    @GetMapping("/current")
    public ShiftStatusResponse current(
            @RequestParam String branchCode, @RequestParam String terminalCode) {
        return shifts.status(branchCode, terminalCode);
    }

    /**
     * POST rather than PATCH, and not idempotent-by-URL: closing carries the count, and a resend of
     * the same close must return the figures the first one froze rather than recount. {@code
     * ShiftService.close} handles that by returning the closed shift unchanged.
     */
    @PostMapping("/{shiftId}/close")
    public ShiftResponse close(
            @PathVariable long shiftId, @Valid @RequestBody CloseShiftRequest request) {
        return shifts.close(shiftId, request);
    }
}

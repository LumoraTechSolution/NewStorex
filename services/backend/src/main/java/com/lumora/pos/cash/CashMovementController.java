package com.lumora.pos.cash;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/** Pay-ins, pay-outs and drops (M2-05). */
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
@RequestMapping("/api/cash-movements")
@Profile("desktop")
public class CashMovementController {

    private final CashMovementService movements;

    public CashMovementController(CashMovementService movements) {
        this.movements = movements;
    }

    @PostMapping
    public CashMovementResponse record(@Valid @RequestBody CreateCashMovementRequest request) {
        return movements.record(request);
    }

    /** What the cash-up screen lists before the count begins. */
    @GetMapping
    public List<CashMovementResponse> forShift(@RequestParam long shiftId) {
        return movements.forShift(shiftId);
    }
}

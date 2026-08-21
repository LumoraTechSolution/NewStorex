package com.lumora.pos.cash;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Pay-ins, pay-outs and drops (M2-05). */
@RestController
@RequestMapping("/api/cash-movements")
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

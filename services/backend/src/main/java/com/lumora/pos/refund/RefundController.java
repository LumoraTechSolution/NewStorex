package com.lumora.pos.refund;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Returns (M2-06 … M2-10). */
@RestController
@RequestMapping("/api/refunds")
public class RefundController {

    private final RefundService refunds;

    public RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    /**
     * The receipt-linked lookup, and the only way in (M2-06).
     *
     * <p>A GET with no manager PIN: looking at what a receipt contained is not a privileged act,
     * and putting the PIN here would train cashiers to type it before they know whether a refund is
     * even going to happen. The PIN is required at {@link #commit}, which is where money moves.
     */
    @GetMapping("/lookup")
    public RefundableSaleResponse lookup(@RequestParam String invoiceNumber) {
        return refunds.lookup(invoiceNumber);
    }

    @PostMapping
    public RefundResponse commit(@Valid @RequestBody CreateRefundRequest request) {
        return refunds.commit(request);
    }
}

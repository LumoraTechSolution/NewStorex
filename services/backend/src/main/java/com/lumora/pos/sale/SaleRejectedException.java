package com.lumora.pos.sale;

import com.lumora.pos.web.RejectedException;

/**
 * The request cannot become a sale.
 *
 * <p>Kept as its own type rather than folded into {@link RejectedException} because the sale path
 * is the one place a caller may want to catch only sale rejections — and because every {@code
 * throw} site in {@code SaleService} reads better naming what it refused.
 */
public class SaleRejectedException extends RejectedException {
    public SaleRejectedException(String message) {
        super(message);
    }
}

package com.lumora.pos.sale;

/** The request cannot become a sale. Always the caller's problem to fix, never a retry. */
public class SaleRejectedException extends RuntimeException {
    public SaleRejectedException(String message) {
        super(message);
    }
}

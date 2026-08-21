package com.lumora.pos.web;

/**
 * The request cannot become what it asked to become. Always the caller's problem to fix, never a
 * retry.
 *
 * <p>That distinction is the whole point of the type. {@link ApiExceptionHandler} maps this to a
 * 422, and the terminal reads a 422 as "stop and tell the cashier"; anything that might succeed on
 * a second attempt must not be thrown as this, because the till will not try again.
 *
 * <p>Introduced in M2 when shifts, cash movements and refunds each needed the same behaviour that
 * {@code SaleRejectedException} already had. Four unrelated exception types mapped to the same
 * status is four chances for one of them to be forgotten in the handler and surface to a shop as a
 * 500 that says "try again" about something that will never work.
 */
public class RejectedException extends RuntimeException {
    public RejectedException(String message) {
        super(message);
    }
}

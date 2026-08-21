package com.lumora.pos.refund;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A sale, as seen from the returns desk (M2-06).
 *
 * <p>The point of this shape is that every cap the backend will enforce is already on it. A cashier
 * is shown what each line still has left and what each tender can still take, so the screen cannot
 * ask for something {@code RefundService} is going to refuse — and if it somehow does, the same
 * numbers are re-derived server-side and the refund is rejected. The till's UI is never the only
 * gate.
 */
public record RefundableSaleResponse(
        long saleId,
        UUID saleClientUuid,
        String invoiceNumber,
        String branchCode,
        String terminalCode,
        Instant soldAt,
        long totalMinor,
        long taxMinor,
        List<Line> lines,
        List<Tender> tenders) {

    /**
     * @param chargedMinor what this line was actually charged, after its own discount and its share
     *     of the order discount — the domain's {@code CartLineTotals.netMinor}. Derived as {@code
     *     unit_price × qty − discount}; see {@code RefundService.refundableLines} for why that
     *     identity holds. This, never {@code line_total_minor}, is what a full return of the line
     *     gives back.
     * @param alreadyRefundedQty and {@code alreadyRefundedMinor} are the two independent caps. A
     *     quantity check alone would allow the right number of units to be returned for the wrong
     *     amount.
     */
    public record Line(
            long saleItemId,
            int lineNo,
            UUID productClientUuid,
            String name,
            int qty,
            long unitPriceMinor,
            long chargedMinor,
            long taxMinor,
            String taxMode,
            int taxRateBp,
            int alreadyRefundedQty,
            long alreadyRefundedMinor) {

        public int refundableQty() {
            return qty - alreadyRefundedQty;
        }

        public long refundableMinor() {
            return chargedMinor - alreadyRefundedMinor;
        }
    }

    /**
     * @param paidMinor what this kind actually contributed to the sale. For cash that is the amount
     *     tendered <em>less the change</em>: {@code sale_payments} records what went on the
     *     counter, and the note that came straight back was never the shop's.
     * @param refundableMinor {@code paid − alreadyRefunded}, floored at zero. A kind with none left
     *     is still listed, so the screen can show why it is unavailable rather than silently
     *     omitting it.
     */
    public record Tender(String kind, long paidMinor, long alreadyRefundedMinor, long refundableMinor) {}
}

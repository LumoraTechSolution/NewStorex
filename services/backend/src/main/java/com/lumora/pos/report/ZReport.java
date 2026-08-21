package com.lumora.pos.report;

import java.time.Instant;
import java.util.List;

/**
 * The end-of-shift report (M2-11).
 *
 * <p>The document a shop files. Its job is to make the variance <em>explicable</em>: a figure with
 * no visible derivation is a figure nobody trusts and everybody overrides, which is the same as not
 * having one. So every term that produced the expected cash is on it, in the order the arithmetic
 * runs.
 *
 * <p>Everything here is read back from what was <em>stored at close</em>, never recomputed. That is
 * the freeze the V107 header describes: a refund raised next week against a sale from this shift
 * would change a recomputed figure, and the shop would then hold two Z-reports disagreeing about
 * one drawer.
 *
 * @param expectedCashMinor and {@code varianceMinor} are null while the shift is still open — a
 *     Z-report of an open shift is a preview, and printing one is how the blind count leaks. {@code
 *     ZReportService} refuses to produce a printable report before close for exactly that reason.
 */
public record ZReport(
        long shiftId,
        String branchCode,
        String terminalCode,
        String status,
        Instant openedAt,
        Instant closedAt,
        long openingFloatMinor,

        // Sales
        int saleCount,
        long grossSalesMinor,
        long discountMinor,
        long taxMinor,
        List<TenderTotal> tendersByKind,
        List<TaxBand> taxByRate,

        // Returns
        int refundCount,
        long refundTotalMinor,
        long refundTaxMinor,

        // The drawer
        long cashSalesMinor,
        long cashChangeMinor,
        long cashRoundingMinor,
        long cashMovementsMinor,
        long cashRefundsMinor,
        long cashRefundRoundingMinor,
        List<CashMovementTotal> cashMovementsByReason,
        Long expectedCashMinor,
        Long countedCashMinor,
        Long varianceMinor,
        String varianceReason,
        String varianceNote,
        List<DenominationLine> closingCount) {

    /** What each payment instrument took across the shift. Cash here is gross of change. */
    public record TenderTotal(String kind, long amountMinor, int lineCount) {}

    /**
     * One rate's worth of the shift (M1-18).
     *
     * <p>Grouped by {@code sale_items.tax_rate_bp}, which since M1-18 is the only thing that can say
     * what tax a sale carried — a basket may mix an exempt line with an 18% one, and the sale-level
     * stamp is the cart default rather than a summary.
     */
    public record TaxBand(int rateBp, String mode, long grossMinor, long taxMinor) {}

    /** Pay-ins, pay-outs and drops, grouped. Amounts stay signed, as stored. */
    public record CashMovementTotal(String kind, String reasonCode, long amountMinor, int count) {}

    /** The closing count, note by note — the evidence behind {@code countedCashMinor}. */
    public record DenominationLine(long denominationMinor, int qty, long subtotalMinor) {}
}

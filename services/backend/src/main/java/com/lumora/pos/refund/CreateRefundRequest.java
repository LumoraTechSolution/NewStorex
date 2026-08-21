package com.lumora.pos.refund;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A return, as the terminal rang it up.
 *
 * <p>The amounts arrive already computed by {@code @lumora/domain}, exactly as a sale's do. That is
 * not laziness: apportioning a partial return so that returning a line in pieces gives back the
 * same total as returning it at once is delicate arithmetic, and a second implementation in Java is
 * the duplication the whole architecture exists to prevent — the one that eventually disagrees with
 * the printed credit note by a rupee.
 *
 * <p>{@code RefundService} enforces what arithmetic cannot: that the sale exists, that a manager
 * allowed it, that nothing goes back twice, and that money goes back the way it came.
 *
 * @param invoiceNumber the number on the customer's receipt. There is no path to a refund that does
 *     not start here (M2-06).
 * @param managerPin M2-07. Sent per refund rather than exchanged for a session token: a token
 *     outlives the manager's attention, and "the manager unlocked it an hour ago" is not
 *     authorisation for this refund. It is never stored, only compared.
 * @param roundingAdjustmentMinor {@code cashPayable − cash allocated} from {@code
 *     allocateRefundTenders}. Signed; the shop absorbs it either way. Zero unless cash is actually
 *     going back across the counter.
 */
public record CreateRefundRequest(
        @NotNull UUID clientUuid,
        @NotBlank String branchCode,
        @NotBlank String terminalCode,
        @NotBlank String invoiceNumber,
        @NotBlank String managerPin,
        Instant refundedAt,
        @NotNull @Min(1) Long totalMinor,
        @NotNull @Min(0) Long taxMinor,
        @NotNull Long roundingAdjustmentMinor,
        @NotEmpty @Valid List<Line> lines,
        @NotEmpty @Valid List<Tender> tenders) {

    /**
     * @param saleLineNo which line of the original sale — {@code sale_items.line_no}. A return is
     *     always "these units of that line", never a loose amount, because the line is what caps
     *     how much can ever come back.
     * @param refundTotalMinor what these units are worth, from {@code refundLineAmounts}. Capped
     *     server-side against what the line was charged less what earlier refunds already took.
     * @param reasonCode required per line, not per document (M2-08): a customer returning one
     *     damaged item and one unwanted one in the same visit is one refund with two reasons, and a
     *     single document-level reason would record neither.
     * @param restock M2-10. False for a damaged item — it is not going back on the shelf, and a
     *     RETURN movement for it would tell the owner they have stock they cannot sell.
     */
    public record Line(
            @NotNull @Min(1) Integer saleLineNo,
            @NotNull @Min(1) Integer qty,
            @NotNull @Min(0) Long refundTotalMinor,
            @NotNull @Min(0) Long taxMinor,
            @NotNull
                    @Pattern(
                            regexp =
                                    "DAMAGED|FAULTY|WRONG_ITEM|EXPIRED|NOT_AS_DESCRIBED|CHANGED_MIND|PRICING_ERROR|OTHER")
                    String reasonCode,
            String note,
            @NotNull Boolean restock) {}

    /**
     * @param kind must be one the sale actually took (M2-09). This is Gate M2's second half, and it
     *     is enforced by the absence of capacity rather than by a warning: a card sale simply has
     *     no cash to give back.
     */
    public record Tender(
            @NotNull @Pattern(regexp = "CASH|CARD|WALLET|STORE_CREDIT") String kind,
            @NotNull @Min(1) Long amountMinor) {}
}

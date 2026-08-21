package com.lumora.pos.refund;

import java.time.Instant;
import java.util.UUID;

/**
 * A refund as recorded.
 *
 * @param creditNoteNumber issued from the terminal's own credit-note block — {@code KND-T1-CN-000004}.
 *     Visibly not an invoice number, and drawn from a separate sequence, so a gap in the invoice
 *     numbers always means a missing invoice rather than a refund (V108).
 * @param alreadyExisted true when this was a retry of a refund already committed. The terminal
 *     prints the same credit note rather than treating it as a second return.
 */
public record RefundResponse(
        long id,
        UUID clientUuid,
        String creditNoteNumber,
        String saleInvoiceNumber,
        long totalMinor,
        long taxMinor,
        long roundingAdjustmentMinor,
        Instant refundedAt,
        boolean alreadyExisted) {}

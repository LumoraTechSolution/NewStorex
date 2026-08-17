package com.lumora.pos.sale;

import java.time.Instant;
import java.util.UUID;

/**
 * What the terminal needs to print a receipt and move on.
 *
 * @param alreadyExisted true when this request was a retry of a sale already committed. The
 *     terminal treats both cases identically — that is the point — but it makes the idempotent path
 *     visible in logs and tests.
 * @param changeMinor echoed back from what was recorded, so a retried request's receipt reprints
 *     the same figure rather than the till re-deriving it from a cart that may have moved on.
 */
public record SaleResponse(
        UUID clientUuid,
        long id,
        String invoiceNumber,
        long totalMinor,
        long changeMinor,
        Instant soldAt,
        boolean alreadyExisted) {}

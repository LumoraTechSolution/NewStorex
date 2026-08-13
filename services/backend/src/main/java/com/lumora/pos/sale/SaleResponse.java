package com.lumora.pos.sale;

import java.time.Instant;
import java.util.UUID;

/**
 * What the terminal needs to print a receipt and move on.
 *
 * @param alreadyExisted true when this request was a retry of a sale already committed. The
 *     terminal treats both cases identically — that is the point — but it makes the idempotent path
 *     visible in logs and tests.
 */
public record SaleResponse(
        UUID clientUuid,
        long id,
        String invoiceNumber,
        long totalMinor,
        Instant soldAt,
        boolean alreadyExisted) {}

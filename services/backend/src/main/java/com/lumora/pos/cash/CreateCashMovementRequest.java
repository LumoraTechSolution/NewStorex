package com.lumora.pos.cash;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;

/**
 * Cash in or out of the drawer other than by a sale (M2-05).
 *
 * @param amountMinor always positive — the cashier types how much, never which way. The sign comes
 *     from {@code kind} and is applied by the service, because a cashier who has to remember a
 *     minus for a pay-out will one day forget, and the drawer will then reconcile to a figure that
 *     is wrong by twice the amount.
 * @param reasonCode required. A pay-out with no reason is indistinguishable from theft, and the
 *     entire point of recording the movement is that the difference should be visible.
 * @param note free text. Mandatory when the reason is {@code OTHER}, which is otherwise a way to
 *     record nothing while appearing to comply.
 */
public record CreateCashMovementRequest(
        @NotNull UUID clientUuid,
        @NotBlank String branchCode,
        @NotBlank String terminalCode,
        Instant occurredAt,
        @NotNull @Pattern(regexp = "PAY_IN|PAY_OUT|DROP") String kind,
        @NotNull @Min(1) Long amountMinor,
        @NotNull
                @Pattern(
                        regexp =
                                "BANK_DROP|SAFE_DROP|SUPPLIER_PAYMENT|PETTY_CASH|CHANGE_FLOAT|OWNER_DRAW|OTHER")
                String reasonCode,
        String note) {}

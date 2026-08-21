package com.lumora.pos.shift;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * One row of a drawer count: how many of a given note or coin (M2-02).
 *
 * <p>The face value is in minor units, like every other amount in this system — {@code 500000} is
 * the LKR 5,000 note. Sending rupees here would read the drawer a hundredfold light, which is why
 * {@code ShiftService} refuses any face value that is not a circulating denomination rather than
 * summing whatever it is given.
 *
 * <p>A qty of zero is legitimate and meaningful: it says the cashier looked and there were none,
 * which is different from not having counted that denomination at all.
 */
public record DenominationCount(
        @NotNull @Min(100) Long denominationMinor, @NotNull @Min(0) Integer qty) {}

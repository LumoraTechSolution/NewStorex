package com.lumora.pos.setup;

import jakarta.validation.constraints.NotBlank;

/**
 * Everything the first-run wizard collects (M5-03).
 *
 * <p>The required fields are the ones without which the till cannot function at all; everything
 * else is nullable because a shop that has not supplied it is in a legitimate state rather than an
 * unfinished one. The distinction is worth being strict about — a wizard that demands a VAT number
 * from a shop that has none is a wizard somebody types zeros into.
 *
 * @param shopName the trading name, printed at the top of every receipt
 * @param branchCode the leading segment of an invoice number — the {@code KND} in
 *     {@code KND-T1-000047}. Letters and digits only, because it is part of a document number.
 * @param branchName what a person calls this branch, e.g. "Kandy Main". Never printed in an
 *     invoice number, so it can be any text.
 * @param terminalCode which till this machine is — the {@code T1}. The single most consequential
 *     field on this form: two tills in one shop sharing it produce duplicate invoice numbers that
 *     stop ingesting in the cloud while both tills go on selling perfectly. See V121.
 * @param shopAddress printed on the receipt. Optional — a receipt without an address is untidy,
 *     while a till that refuses to open without one is broken.
 * @param supplierTin the VAT registration number, and {@code supplierRegisteredName} /
 *     {@code supplierAddress} the identity as the certificate records it (M5-09). All three are
 *     optional together: a shop that is not VAT registered has none of them, and
 *     {@code TaxInvoiceService} already refuses to issue a tax invoice when they are missing
 *     rather than printing a guess. Deliberately separate from {@code shopAddress} — a shop may
 *     trade from one address and be registered at another.
 * @param ownerCode the code the owner types to sign in, e.g. {@code OWNER}. Short and
 *     upper-cased, like every other user code (V109).
 * @param ownerName who they are, shown on screen and against every sale they ring up
 * @param ownerPin their PIN. The only secret on this form, never echoed back, and hashed by
 *     {@code UserService} rather than here so there is one BCrypt cost in the codebase.
 */
public record ShopSetupRequest(
        @NotBlank String shopName,
        @NotBlank String branchCode,
        @NotBlank String branchName,
        @NotBlank String terminalCode,
        String shopAddress,
        String supplierTin,
        String supplierRegisteredName,
        String supplierAddress,
        @NotBlank String ownerCode,
        @NotBlank String ownerName,
        @NotBlank String ownerPin) {}

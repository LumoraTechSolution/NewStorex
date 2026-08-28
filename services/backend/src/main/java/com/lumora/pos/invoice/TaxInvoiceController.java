package com.lumora.pos.invoice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issuing and reprinting the IRD tax invoice (M5-09).
 *
 * <p>Desktop profile only. The document is produced by the till that made the sale, offline, from
 * that terminal's own serial block — the same property every other numbered document here has.
 */
@RestController
@RequestMapping("/api/tax-invoices")
@Profile("desktop")
public class TaxInvoiceController {

    private final TaxInvoiceService taxInvoices;

    public TaxInvoiceController(TaxInvoiceService taxInvoices) {
        this.taxInvoices = taxInvoices;
    }

    @PostMapping
    public TaxInvoiceService.TaxInvoice issue(@Valid @RequestBody IssueTaxInvoiceRequest request) {
        return taxInvoices.issue(request.saleInvoiceNumber(), request.toPurchaser());
    }

    /** What has already been issued against a sale, so the till reprints rather than re-issues. */
    @GetMapping("/for-sale/{saleInvoiceNumber}")
    public List<TaxInvoiceService.TaxInvoice> forSale(@PathVariable String saleInvoiceNumber) {
        return taxInvoices.findBySaleInvoiceNumber(saleInvoiceNumber);
    }

    /**
     * @param purchaserTin nine digits, or absent for a walk-in consumer. Circular SEC/2026/E/03
     *     §4.3 requires purchaser particulars only where the purchaser is VAT-registered, so an
     *     absent TIN is the ordinary case and not an omission.
     */
    public record IssueTaxInvoiceRequest(
            @NotBlank String saleInvoiceNumber,
            @Pattern(regexp = "^[0-9]{9}$", message = "A TIN is exactly nine digits")
                    String purchaserTin,
            String purchaserName,
            String purchaserAddress) {

        TaxInvoiceService.Purchaser toPurchaser() {
            if (purchaserTin == null || purchaserTin.isBlank()) {
                return null;
            }
            // The database enforces all-or-nothing; this turns that into a sentence a cashier can
            // act on rather than a constraint violation.
            if (isBlank(purchaserName) || isBlank(purchaserAddress)) {
                throw new com.lumora.pos.web.RejectedException(
                        "A VAT-registered purchaser needs a name and an address as well as a TIN.");
            }
            return new TaxInvoiceService.Purchaser(
                    purchaserTin.trim(), purchaserName.trim(), purchaserAddress.trim());
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}

package com.lumora.pos.sale;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sales")
public class SaleController {

    private final SaleService sales;

    public SaleController(SaleService sales) {
        this.sales = sales;
    }

    /**
     * Commits a sale. Safe to retry: the terminal generates the {@code clientUuid} before sending,
     * so a request that times out can simply be sent again — the second attempt returns the first
     * one's result rather than ringing up a second sale.
     *
     * @return 201 when the sale was created, 200 when this was a retry of one already committed
     */
    @PostMapping
    public ResponseEntity<SaleResponse> create(@Valid @RequestBody CreateSaleRequest request) {
        SaleResponse response = sales.commit(request);
        return ResponseEntity.status(response.alreadyExisted() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }
}

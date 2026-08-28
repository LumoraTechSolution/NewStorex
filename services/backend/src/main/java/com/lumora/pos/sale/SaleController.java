package com.lumora.pos.sale;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/*
 * Desktop profile only.
 *
 * <p>Without this the class is a bean under every profile, so the cloud instance mounted it too —
 * behind M4-01's filter, but mounted. Everything it calls goes through {@code LocalShop}, which
 * asserts the database holds exactly one tenant, so on the cloud it could only ever fail. A route
 * that exists and always fails is worse than one that does not exist: it is a promise in the URL
 * space that somebody eventually tries to keep.
 */
@RestController
@RequestMapping("/api/sales")
@Profile("desktop")
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

package com.lumora.pos.sync;

import com.lumora.pos.cloud.AuthenticatedPrincipal;
import com.lumora.pos.cloud.CloudPrincipals;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cloud's ingest endpoint. Exists only under the {@code cloud} profile — a shop PC must never
 * accidentally become somewhere another till can push to.
 */
@RestController
@RequestMapping("/api/sync")
@Profile("cloud")
public class SyncController {

    private final SyncIngestService ingest;

    public SyncController(SyncIngestService ingest) {
        this.ingest = ingest;
    }

    /**
     * Accepts a drained batch and reports what stuck. Always 200: per-item outcomes live in the
     * body, because "some of this worked" is the normal case, not an error.
     *
     * <p>The tenant is read from the request attribute the auth filter set, so reaching this method
     * at all means a valid credential was presented. An unauthenticated request never arrives here
     * — it was answered 401 by the filter.
     *
     * <p>A <b>till</b> credential specifically: an owner's console session must not be able to push
     * sales into their own ledger. Read-only means read-only on both sides of the wire.
     */
    @PostMapping("/batch")
    public SyncBatchResult batch(@Valid @RequestBody SyncBatch batch, HttpServletRequest request) {
        return ingest.ingest(CloudPrincipals.require(request, AuthenticatedPrincipal.Kind.TILL).tenantId(), batch);
    }
}

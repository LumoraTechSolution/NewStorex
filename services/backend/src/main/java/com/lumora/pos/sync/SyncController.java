package com.lumora.pos.sync;

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
     */
    @PostMapping("/batch")
    public SyncBatchResult batch(@Valid @RequestBody SyncBatch batch) {
        return ingest.ingest(batch);
    }
}

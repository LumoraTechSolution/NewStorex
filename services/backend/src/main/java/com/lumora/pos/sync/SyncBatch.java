package com.lumora.pos.sync;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** One drain of a shop's outbox, on its way to the cloud. */
public record SyncBatch(
        @NotNull UUID tenantClientUuid, @NotBlank String tenantName, @NotEmpty @Valid List<Item> items) {

    /**
     * @param aggregateId the aggregate's {@code client_uuid} — the key everything upserts on
     * @param payload left as a {@link JsonNode} on purpose: the cloud should be able to accept an
     *     aggregate kind it does not fully understand yet, rather than rejecting a whole batch
     *     because a till is running a newer build.
     */
    public record Item(
            @NotBlank String aggregate, @NotNull UUID aggregateId, @NotNull JsonNode payload) {}
}

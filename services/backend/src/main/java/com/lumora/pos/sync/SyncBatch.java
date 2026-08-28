package com.lumora.pos.sync;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * One drain of a shop's outbox, on its way to the cloud.
 *
 * <p>It carries no tenant. Until M4-01 it carried {@code tenantClientUuid} and {@code tenantName},
 * and the cloud created the tenant on first sight — which meant the caller chose which shop it was
 * writing into. The tenant now comes from the bearer token on the request and cannot be expressed
 * here at all: a claim that does not exist in the payload is one no future reader can be tempted to
 * trust.
 */
public record SyncBatch(@NotEmpty @Valid List<Item> items) {

    /**
     * @param aggregateId the aggregate's {@code client_uuid} — the key everything upserts on
     * @param payload left as a {@link JsonNode} on purpose: the cloud should be able to accept an
     *     aggregate kind it does not fully understand yet, rather than rejecting a whole batch
     *     because a till is running a newer build.
     */
    public record Item(
            @NotBlank String aggregate, @NotNull UUID aggregateId, @NotNull JsonNode payload) {}
}

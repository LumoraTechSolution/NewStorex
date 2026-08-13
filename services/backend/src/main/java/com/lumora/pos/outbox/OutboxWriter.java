package com.lumora.pos.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes the sync record for an aggregate.
 *
 * <p>There is no {@code @Transactional} here on purpose. This must always join the caller's
 * transaction — the one that wrote the domain rows — so that a sale can never exist without its
 * outbox row. A method that started its own transaction would commit the sync record independently
 * and quietly break the guarantee the whole architecture rests on.
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * @param aggregate the kind: {@code sale}, {@code refund}, {@code shift}, {@code product}, …
     * @param aggregateId the aggregate's {@code client_uuid}. The cloud upserts on it, which is what
     *     makes redelivery a no-op and retries always safe.
     */
    public void enqueue(long tenantId, String aggregate, UUID aggregateId, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not serialise the " + aggregate + " payload for the outbox", e);
        }

        jdbc.update(
                """
                INSERT INTO outbox (tenant_id, aggregate, aggregate_id, payload)
                VALUES (?, ?, ?, ?::jsonb)
                """,
                tenantId,
                aggregate,
                aggregateId,
                json);
    }
}

package com.lumora.pos.sync;

import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP transport for the outbox drain.
 *
 * <p>Timeouts are short and explicit. A hung connection here would keep a worker thread parked, and
 * while that never blocks a sale — nothing in the shop waits on this — it does stall the queue
 * behind it, so a till that looks online stays silently unsynced.
 */
@Component
@Profile("desktop")
public class HttpCloudSyncClient implements CloudSyncClient {

    private final RestClient restClient;

    public HttpCloudSyncClient(SyncProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) properties.requestTimeout().toMillis());

        this.restClient =
                RestClient.builder().baseUrl(properties.cloudUrl()).requestFactory(factory).build();
    }

    @Override
    public SyncBatchResult push(SyncBatch batch) {
        try {
            SyncBatchResult result =
                    restClient.post().uri("/api/sync/batch").body(batch).retrieve().body(SyncBatchResult.class);
            if (result == null) {
                throw new CloudUnreachableException("Cloud returned an empty body", null);
            }
            return result;
        } catch (CloudUnreachableException e) {
            throw e;
        } catch (Exception e) {
            // Everything from a refused connection to a 500 is "try again later". The shop is
            // unaffected either way; the rows simply stay in the outbox.
            throw new CloudUnreachableException("Could not reach the cloud: " + e.getMessage(), e);
        }
    }
}

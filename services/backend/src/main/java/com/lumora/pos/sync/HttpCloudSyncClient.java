package com.lumora.pos.sync;

import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
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

        RestClient.Builder builder =
                RestClient.builder().baseUrl(properties.cloudUrl()).requestFactory(factory);

        // Which shop this is (M4-01). It rides on every request as a header rather than in the
        // body, so it is never written into an outbox row, never logged with a payload, and never
        // something the batch itself can contradict.
        if (properties.hasToken()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token());
        }

        this.restClient = builder.build();
    }

    @Override
    public Entitlement fetchEntitlement() {
        try {
            Entitlement entitlement =
                    restClient.get().uri("/api/sync/entitlement").retrieve().body(Entitlement.class);
            if (entitlement == null) {
                throw new CloudUnreachableException("Cloud returned an empty entitlement", null);
            }
            return entitlement;
        } catch (CloudUnreachableException e) {
            throw e;
        } catch (Exception e) {
            // Including the 401 a revoked or unactivated till gets. There is nothing useful to do
            // about it here and nothing to change: the cached answer stands, and the shop keeps
            // working on it. The push failing is what will actually be noticed, and it is louder.
            throw new CloudUnreachableException(
                    "Could not read the entitlement: " + e.getMessage(), e);
        }
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

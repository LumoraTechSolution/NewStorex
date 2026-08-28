package com.lumora.pos.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumora.pos.sync.SyncBatch;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * That a shop can only reach its own data (M4-01).
 *
 * <p>Over real HTTP, unlike the rest of the cloud tests. Everything asserted here is enforced by
 * {@link TenantAuthFilter}, and a filter is precisely the part of the stack that a test calling the
 * service directly cannot see — a suite that only ever called {@code ingest.ingest(tenantId, …)}
 * would pass just as happily with no authentication wired up at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"cloud", "test"})
@TestPropertySource(
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5444/lumora_test_cloud")
class TenantIsolationTest {

    @LocalServerPort int port;

    @Autowired TenantCredentialService credentials;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private RestTemplate http;

    /**
     * Built here rather than injecting {@code TestRestTemplate}, for one reason that matters to
     * every 401 in this class.
     *
     * <p>The default transport is the legacy {@code HttpURLConnection}, which on a 401 to a request
     * that had a body throws {@code HttpRetryException} — "cannot retry due to server
     * authentication" — instead of handing back the response. It is trying to re-send the request
     * with credentials, which is exactly the wrong instinct here: the 401 <em>is</em> the result.
     * Buffering the body does not avoid it. {@code JdkClientHttpRequestFactory} uses
     * {@code java.net.http.HttpClient}, which has no such behaviour and needs no extra dependency.
     *
     * <p>The error handler is replaced too: here a 4xx is the assertion, not a failure.
     */
    @BeforeEach
    void buildAClientThatCanReadA401() {
        http = new RestTemplate(new JdkClientHttpRequestFactory());
        http.setErrorHandler(
                new DefaultResponseErrorHandler() {
                    @Override
                    public boolean hasError(org.springframework.http.client.ClientHttpResponse r) {
                        return false;
                    }
                });
    }

    // ------------------------------------------------------------------ the door is locked

    @Test
    void aBatchWithNoTokenIsRejected() {
        UUID saleUuid = UUID.randomUUID();

        ResponseEntity<String> response = push(null, batch(saleUuid, "KND-T1-000001"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The rejection has to happen before ingest, not after it — a 401 on a request that
        // already wrote the row would be theatre.
        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", saleUuid)).isZero();
    }

    @Test
    void aBatchWithAnUnknownTokenIsRejected() {
        ResponseEntity<String> response =
                push("lum_not-a-token-anybody-ever-issued", batch(UUID.randomUUID(), "KND-T1-000002"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** The reason a credential is a row rather than a column: a stolen till can be cut off. */
    @Test
    void aRevokedCredentialStopsWorking() {
        var provisioned = credentials.provision("Kandy Stores", "Till 1");
        assertThat(push(provisioned.token(), batch(UUID.randomUUID(), "KND-T1-000003")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        jdbc.update("UPDATE tenant_api_credentials SET revoked_at = now() WHERE tenant_id = ?",
                provisioned.tenantId());

        assertThat(push(provisioned.token(), batch(UUID.randomUUID(), "KND-T1-000004")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * A lapsed licence stops ingest and touches nothing else. The sale that already landed is still
     * there — suspending a shop must never look like deleting it (M4-09).
     */
    @Test
    void aSuspendedTenantCannotPushButKeepsItsHistory() {
        var provisioned = credentials.provision("Galle Stores", "Till 1");
        push(provisioned.token(), batch(UUID.randomUUID(), "GAL-T1-000001"));

        jdbc.update("UPDATE tenants SET active = false WHERE id = ?", provisioned.tenantId());

        assertThat(push(provisioned.token(), batch(UUID.randomUUID(), "GAL-T1-000002")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(count("SELECT count(*) FROM sales WHERE tenant_id = ?", provisioned.tenantId()))
                .isEqualTo(1);
    }

    /** Every rejection is the same rejection — otherwise the 401 tells a stranger what exists. */
    @Test
    void anUnknownTokenAndARevokedOneAreIndistinguishable() {
        var provisioned = credentials.provision("Matara Stores", "Till 1");
        jdbc.update("UPDATE tenant_api_credentials SET revoked_at = now() WHERE tenant_id = ?",
                provisioned.tenantId());

        ResponseEntity<String> revoked = push(provisioned.token(), batch(UUID.randomUUID(), "MAT-T1-000001"));
        ResponseEntity<String> unknown = push("lum_never-existed", batch(UUID.randomUUID(), "MAT-T1-000002"));

        assertThat(revoked.getStatusCode()).isEqualTo(unknown.getStatusCode());
        assertThat(revoked.getBody()).isEqualTo(unknown.getBody());
    }

    // ------------------------------------------------------------- the door opens onto one shop

    @Test
    void aValidTokenLandsTheSaleUnderItsOwnTenant() {
        var mine = credentials.provision("Kandy Stores", "Till 1");
        var theirs = credentials.provision("Galle Stores", "Till 1");
        UUID saleUuid = UUID.randomUUID();

        assertThat(push(mine.token(), batch(saleUuid, "KND-T1-000010")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ? AND tenant_id = ?",
                        saleUuid, mine.tenantId()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM sales WHERE tenant_id = ?", theirs.tenantId())).isZero();
    }

    /**
     * The V206 property, and the reason the unique index had to move.
     *
     * <p>Two shops sending the same {@code client_uuid} are sending two different sales. Under the
     * old global unique index the second one landed on the first one's row: the till was told
     * "accepted" and the sale then existed nowhere it could be reported from. Random v4 uuids make
     * that vanishingly unlikely by accident, which is exactly why it would never have been found
     * except by somebody doing it on purpose.
     */
    @Test
    void twoTenantsSendingTheSameUuidGetTwoSales() {
        var first = credentials.provision("Kandy Stores", "Till 1");
        var second = credentials.provision("Galle Stores", "Till 1");
        UUID shared = UUID.randomUUID();

        assertThat(push(first.token(), batch(shared, "KND-T1-000020")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(push(second.token(), batch(shared, "GAL-T1-000020")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", shared)).isEqualTo(2);
        assertThat(invoiceFor(shared, first.tenantId())).isEqualTo("KND-T1-000020");
        assertThat(invoiceFor(shared, second.tenantId())).isEqualTo("GAL-T1-000020");
    }

    /** Idempotency still holds — it is the same tenant redelivering, so the conflict still fires. */
    @Test
    void redeliveryIsStillANoOpNowThatTheKeyCarriesTheTenant() {
        var tenant = credentials.provision("Kandy Stores", "Till 1");
        UUID saleUuid = UUID.randomUUID();
        SyncBatch batch = batch(saleUuid, "KND-T1-000030");

        push(tenant.token(), batch);
        push(tenant.token(), batch);
        push(tenant.token(), batch);

        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", saleUuid)).isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM sale_items WHERE sale_id = (SELECT id FROM sales WHERE client_uuid = ?)",
                                saleUuid))
                .isEqualTo(1);
    }

    // --------------------------------------------------------------------------- credentials

    @Test
    void aTokenIsNeverStoredInTheClear() {
        var provisioned = credentials.provision("Kandy Stores", "Till 1");

        assertThat(count("SELECT count(*) FROM tenant_api_credentials WHERE token_hash = ?",
                        provisioned.token()))
                .isZero();
        assertThat(count("SELECT count(*) FROM tenant_api_credentials WHERE token_hash = ?",
                        TenantCredentialService.hash(provisioned.token())))
                .isEqualTo(1);
    }

    @Test
    void everyIssuedTokenIsDifferent() {
        var first = credentials.provision("Kandy Stores", "Till 1");
        String second = credentials.issueToken(first.tenantId(), "Till 2");

        assertThat(second).isNotEqualTo(first.token()).startsWith("lum_");
    }

    /** A second till on the same shop reaches the same tenant — and can be revoked on its own. */
    @Test
    void asecondCredentialReachesTheSameTenant() {
        var shop = credentials.provision("Kandy Stores", "Till 1");
        String tillTwo = credentials.issueToken(shop.tenantId(), "Till 2");

        push(shop.token(), batch(UUID.randomUUID(), "KND-T1-000040"));
        push(tillTwo, batch(UUID.randomUUID(), "KND-T2-000001"));

        assertThat(count("SELECT count(*) FROM sales WHERE tenant_id = ?", shop.tenantId())).isEqualTo(2);
    }

    @Test
    void authenticatingRecordsThatTheTillCalled() {
        var tenant = credentials.provision("Kandy Stores", "Till 1");
        assertThat(count("SELECT count(*) FROM tenant_api_credentials WHERE tenant_id = ? AND last_seen_at IS NOT NULL",
                        tenant.tenantId()))
                .isZero();

        push(tenant.token(), batch(UUID.randomUUID(), "KND-T1-000050"));

        assertThat(count("SELECT count(*) FROM tenant_api_credentials WHERE tenant_id = ? AND last_seen_at IS NOT NULL",
                        tenant.tenantId()))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------------------- helpers

    private ResponseEntity<String> push(String token, SyncBatch batch) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(
                "http://localhost:" + port + "/api/sync/batch",
                HttpMethod.POST,
                new HttpEntity<>(batch, headers),
                String.class);
    }

    private SyncBatch batch(UUID saleUuid, String invoiceNumber) {
        String payload =
                """
                {
                  "clientUuid": "%s",
                  "branchCode": "KND",
                  "terminalCode": "T1",
                  "invoiceNumber": "%s",
                  "soldAt": "2026-08-12T04:30:00Z",
                  "taxMode": "INCLUSIVE",
                  "taxRateBp": 1800,
                  "subtotalMinor": 90000,
                  "discountMinor": 0,
                  "taxMinor": 13728,
                  "totalMinor": 90000,
                  "lines": [{
                    "productClientUuid": "00000000-0000-4000-8000-000000000101",
                    "lineNo": 1, "qty": 2,
                    "unitPriceMinor": 45000, "discountMinor": 0,
                    "taxMinor": 13728, "lineTotalMinor": 90000
                  }]
                }
                """
                        .formatted(saleUuid, invoiceNumber);
        try {
            return new SyncBatch(
                    List.of(new SyncBatch.Item("sale", saleUuid, objectMapper.readTree(payload))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String invoiceFor(UUID clientUuid, long tenantId) {
        return jdbc.queryForObject(
                "SELECT invoice_number FROM sales WHERE client_uuid = ? AND tenant_id = ?",
                String.class,
                clientUuid,
                tenantId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}

package com.lumora.pos.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumora.pos.cloud.ConsoleUserService;
import com.lumora.pos.cloud.LicenceService;
import com.lumora.pos.cloud.TenantCredentialService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * The cloud's half of the downward pull (M4-09), over real HTTP.
 *
 * <p>Real HTTP for the same reason the other cloud tests use it: the rule that matters most here
 * lives in {@code TenantAuthFilter}, and a test calling the controller directly would pass with the
 * filter's allowlist deleted. The central test below is exactly that — a token that is refused
 * everywhere else being accepted here — and it is meaningless without the filter in the path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"cloud", "test"})
@TestPropertySource(
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5444/lumora_test_cloud")
class EntitlementFeedTest {

    @LocalServerPort int port;

    @Autowired TenantCredentialService tillCredentials;
    @Autowired ConsoleUserService consoleUsers;
    @Autowired LicenceService licences;
    @Autowired JdbcTemplate jdbc;

    private RestTemplate http;

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

    @Test
    void aLicensedTillIsToldItsPlanItsExpiryAndItsCapabilities() {
        var provisioned = tillCredentials.provision("Entitled Stores", "Till 1");

        ResponseEntity<JsonNode> response = getEntitlement(provisioned.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("licensed").asBoolean()).isTrue();
        assertThat(body.get("planCode").asText()).isEqualTo(LicenceService.DEFAULT_PLAN_CODE);
        assertThat(body.get("licenceExpiresAt").asText()).isNotBlank();
        // The trial carries every flag in the registry, so this is also an assertion that flags
        // travel at all rather than arriving as an empty list nobody notices.
        assertThat(body.get("flags")).isNotEmpty();
    }

    /**
     * <b>The reason this endpoint exists.</b>
     *
     * <p>A lapsed licence stops ingest — that is V209's commercial lever and the previous
     * assertion here confirms it still bites. But the shop that has lapsed is precisely the shop
     * that needs to be told, and if the news travelled on the batch response it could only ever
     * reach a shop that had not lapsed. The till would see 401s and no explanation, and the cashier
     * would see an offline strip that never clears.
     *
     * <p>So the same token that is refused at the ingest door is accepted at this one, and answers
     * with the plan and the date it ran out.
     */
    @Test
    void aLapsedTillIsRefusedIngestAndStillLearnsWhyFromTheSameToken() {
        var provisioned = tillCredentials.provision("Lapsed But Informed", "Till 1");
        expireEveryLicenceOf(provisioned.tenantId());

        assertThat(knockOnIngest(provisioned.token()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<JsonNode> response = getEntitlement(provisioned.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("licensed").asBoolean()).isFalse();
        // Named, and dated. "Unlicensed" on its own is a mystery the owner has to ring somebody
        // about; "Trial, ended yesterday" is a renewal notice they can act on.
        assertThat(body.get("planCode").asText()).isEqualTo(LicenceService.DEFAULT_PLAN_CODE);
        assertThat(body.get("licenceExpiresAt").asText()).isNotBlank();
        // No capabilities travel with a lapse. What the till does with that — keep the last
        // licensed set and show a notice — is its own decision, asserted in EntitlementStoreTest.
        assertThat(body.get("flags")).isEmpty();
    }

    /**
     * The relaxation is one path wide. A revoked credential is still nobody here, which is the
     * check that stops "tolerates a lapse" from quietly becoming "tolerates anything".
     */
    @Test
    void aRevokedCredentialLearnsNothing() {
        var provisioned = tillCredentials.provision("Revoked Stores", "Till 1");
        long credentialId =
                jdbc.queryForObject(
                        "SELECT id FROM tenant_api_credentials WHERE tenant_id = ?",
                        Long.class,
                        provisioned.tenantId());
        tillCredentials.revoke(credentialId);

        assertThat(getEntitlement(provisioned.token()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anUnknownTokenLearnsNothing() {
        assertThat(getEntitlement("lum_not-a-real-token").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getEntitlement(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * A till endpoint, and only a till endpoint. An owner's session reads their plan through the
     * console API where the rest of their reads live; letting it in here would make the credential
     * kinds interchangeable at one more place, which is the drift {@code AuthenticatedPrincipal}
     * exists to prevent.
     */
    @Test
    void anOwnerSessionIsTheWrongKindOfCredentialForIt() {
        var provisioned = tillCredentials.provision("Owner Curious Stores", "Till 1");
        consoleUsers.create(
                provisioned.tenantId(), "owner-entitlement@curious.lk", "a-long-password", "Owner");

        ResponseEntity<JsonNode> login =
                http.exchange(
                        url("/api/console/auth/login"),
                        HttpMethod.POST,
                        new HttpEntity<>(
                                """
                                {"email":"owner-entitlement@curious.lk","password":"a-long-password"}""",
                                jsonHeaders(null)),
                        JsonNode.class);
        String session = login.getBody().get("token").asText();

        assertThat(getEntitlement(session).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<JsonNode> getEntitlement(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(
                url("/api/sync/entitlement"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class);
    }

    /** See {@code PlatformAdminTest#knockOnIngest} on why the batch carries an unknown aggregate. */
    private ResponseEntity<String> knockOnIngest(String token) {
        String body =
                """
                {"items":[{"aggregate":"not_a_real_aggregate","aggregateId":"%s","payload":{}}]}"""
                        .formatted(UUID.randomUUID());
        return http.exchange(
                url("/api/sync/batch"),
                HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(token)),
                String.class);
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    /** See {@code PlatformAdminTest#expireEveryLicenceOf} — an UPDATE nothing in production does. */
    private void expireEveryLicenceOf(long tenantId) {
        jdbc.update(
                """
                UPDATE tenant_licences
                   SET starts_at = now() - interval '60 days', expires_at = now() - interval '1 day'
                 WHERE tenant_id = ?
                """,
                tenantId);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}

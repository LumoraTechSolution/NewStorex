package com.lumora.pos.cloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumora.pos.sync.SyncBatch;
import com.lumora.pos.web.RejectedException;
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
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * The owner signing in, and the wall between a person's session and a machine's token (M4-05).
 *
 * <p>Over real HTTP, like {@link TenantIsolationTest} and for the same reason: the rules asserted
 * here live in a filter, and a test calling the services directly would pass with no filter wired
 * up at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"cloud", "test"})
@TestPropertySource(
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5444/lumora_test_cloud")
class ConsoleAuthTest {

    private static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort int port;

    @Autowired ConsoleUserService consoleUsers;
    @Autowired ConsoleSessionService sessions;
    @Autowired TenantCredentialService tillCredentials;
    @Autowired JdbcTemplate jdbc;

    private RestTemplate http;

    /** See {@link TenantIsolationTest} on why this transport and not {@code TestRestTemplate}. */
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

    // ------------------------------------------------------------------------ signing in

    @Test
    void anOwnerSignsInAndGetsAToken() {
        Owner owner = provisionOwner("owner@kandystores.lk");

        ResponseEntity<JsonNode> response = login(owner.email(), PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("token").asText()).startsWith("lums_");
        assertThat(response.getBody().get("email").asText()).isEqualTo(owner.email());
    }

    @Test
    void theWrongPasswordIsRefused() {
        Owner owner = provisionOwner("owner2@kandystores.lk");

        assertThat(login(owner.email(), "not the password").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The two failures must be indistinguishable. A message that says "no such account" hands
     * anybody holding a list of email addresses a way to find out which are real.
     */
    @Test
    void anUnknownEmailAndAWrongPasswordLookIdentical() {
        Owner owner = provisionOwner("owner3@kandystores.lk");

        ResponseEntity<JsonNode> wrongPassword = login(owner.email(), "not the password");
        ResponseEntity<JsonNode> noSuchAccount = login("nobody@example.com", PASSWORD);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(noSuchAccount.getStatusCode());
        assertThat(wrongPassword.getBody()).isEqualTo(noSuchAccount.getBody());
    }

    @Test
    void emailIsCaseAndWhitespaceInsensitive() {
        provisionOwner("owner4@kandystores.lk");

        assertThat(login("  OWNER4@KandyStores.LK  ", PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aDeactivatedOwnerCannotSignIn() {
        Owner owner = provisionOwner("owner5@kandystores.lk");
        jdbc.update("UPDATE console_users SET active = false WHERE email = ?", owner.email());

        assertThat(login(owner.email(), PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** A lapsed licence locks the owner out and touches not one row of their history (M4-09). */
    @Test
    void aSuspendedTenantLocksItsOwnerOut() {
        Owner owner = provisionOwner("owner6@kandystores.lk");
        jdbc.update("UPDATE tenants SET active = false WHERE id = ?", owner.tenantId());

        assertThat(login(owner.email(), PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(count("SELECT count(*) FROM console_users WHERE email = ?", owner.email()))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------------ using a session

    @Test
    void theSessionIdentifiesItsOwnerAndTenant() {
        Owner owner = provisionOwner("owner7@kandystores.lk");
        String token = tokenFor(owner);

        ResponseEntity<JsonNode> me = get("/api/console/auth/me", token);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("email").asText()).isEqualTo(owner.email());
        assertThat(me.getBody().get("tenantId").asLong()).isEqualTo(owner.tenantId());
    }

    @Test
    void anUnknownSessionTokenIsRejected() {
        assertThat(get("/api/console/auth/me", "lums_never-issued").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** The reason the token is opaque and the session is a row: signing out has to mean something. */
    @Test
    void signingOutEndsTheSessionImmediately() {
        Owner owner = provisionOwner("owner8@kandystores.lk");
        String token = tokenFor(owner);
        assertThat(get("/api/console/auth/me", token).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(post("/api/console/auth/logout", token).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(get("/api/console/auth/me", token).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Deactivation must bite on the next request, not when the session happens to expire. */
    @Test
    void deactivatingAnOwnerKillsTheirLiveSession() {
        Owner owner = provisionOwner("owner9@kandystores.lk");
        String token = tokenFor(owner);

        jdbc.update("UPDATE console_users SET active = false WHERE email = ?", owner.email());

        assertThat(get("/api/console/auth/me", token).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anExpiredSessionIsRejected() {
        Owner owner = provisionOwner("owner10@kandystores.lk");
        String token = tokenFor(owner);

        jdbc.update("UPDATE console_sessions SET expires_at = now() - interval '1 minute'");

        assertThat(get("/api/console/auth/me", token).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------- the wall between the two credential kinds

    /**
     * The property {@link AuthenticatedPrincipal} exists for.
     *
     * <p>A till's token is soldered into a machine and never expires. If it also opened the owner's
     * reporting, anybody with physical access to a terminal would have the whole business — and the
     * request would simply succeed, silently.
     */
    @Test
    void aTillTokenCannotUseTheConsole() {
        var provisioned = tillCredentials.provision("Kandy Stores", "Till 1");

        ResponseEntity<JsonNode> response = get("/api/console/auth/me", provisioned.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** And the other way: the console is read-only, on the wire as well as in the UI. */
    @Test
    void aConsoleSessionCannotPushSales() {
        Owner owner = provisionOwner("owner11@kandystores.lk");
        String token = tokenFor(owner);

        ResponseEntity<JsonNode> response = postBatch(token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(count("SELECT count(*) FROM sales WHERE tenant_id = ?", owner.tenantId())).isZero();
    }

    /** 403 rather than 401 — a till told to re-authenticate would loop forever on the same token. */
    @Test
    void theWrongKindIsForbiddenRatherThanUnauthorized() {
        var provisioned = tillCredentials.provision("Galle Stores", "Till 1");

        assertThat(get("/api/console/auth/me", provisioned.token()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN)
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------------ account rules

    @Test
    void anEmailCanOnlyBeUsedOnce() {
        Owner owner = provisionOwner("duplicate@kandystores.lk");

        assertThatThrownBy(
                        () ->
                                consoleUsers.create(
                                        owner.tenantId(), "DUPLICATE@kandystores.lk", PASSWORD, "Someone Else"))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("already an account");
    }

    @Test
    void aShortPasswordIsRefused() {
        long tenantId = tillCredentials.provision("Matara Stores", "Till 1").tenantId();

        assertThatThrownBy(() -> consoleUsers.create(tenantId, "short@example.com", "abc", "Owner"))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("at least");
    }

    @Test
    void thePasswordIsNeverStoredInTheClear() {
        Owner owner = provisionOwner("hashed@kandystores.lk");

        String stored =
                jdbc.queryForObject(
                        "SELECT password_hash FROM console_users WHERE email = ?",
                        String.class,
                        owner.email());

        assertThat(stored).doesNotContain(PASSWORD).startsWith("$2");
    }

    @Test
    void signingInIsRecorded() {
        Owner owner = provisionOwner("seen@kandystores.lk");
        tokenFor(owner);

        assertThat(
                        count(
                                "SELECT count(*) FROM console_users WHERE email = ? AND last_login_at IS NOT NULL",
                                owner.email()))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------------------- helpers

    private record Owner(long tenantId, String email) {}

    private Owner provisionOwner(String email) {
        long tenantId = tillCredentials.provision("Kandy Stores", "Till 1").tenantId();
        consoleUsers.create(tenantId, email, PASSWORD, "Kandy Owner");
        return new Owner(tenantId, email);
    }

    private String tokenFor(Owner owner) {
        return login(owner.email(), PASSWORD).getBody().get("token").asText();
    }

    private ResponseEntity<JsonNode> login(String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(
                url("/api/console/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(new ConsoleAuthController.LoginRequest(email, password), headers),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path, String token) {
        return http.exchange(
                url(path), HttpMethod.GET, new HttpEntity<>(authorised(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String path, String token) {
        return http.exchange(
                url(path), HttpMethod.POST, new HttpEntity<>(authorised(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> postBatch(String token) {
        HttpHeaders headers = authorised(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        SyncBatch batch =
                new SyncBatch(
                        List.of(
                                new SyncBatch.Item(
                                        "sale",
                                        UUID.randomUUID(),
                                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                                .objectNode())));
        return http.exchange(
                url("/api/sync/batch"), HttpMethod.POST, new HttpEntity<>(batch, headers), JsonNode.class);
    }

    private static HttpHeaders authorised(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}

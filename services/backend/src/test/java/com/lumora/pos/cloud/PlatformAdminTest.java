package com.lumora.pos.cloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumora.pos.web.RejectedException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * The super-admin: signing a shop up, licensing it, and the wall around what staff may do (M4-08).
 *
 * <p>Over real HTTP, like the other cloud tests and for the same reason — the rules that matter here
 * live in {@link TenantAuthFilter} and in {@link CloudPrincipals}, and a test calling the services
 * directly would pass with no authentication wired up at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"cloud", "test"})
@TestPropertySource(
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5444/lumora_test_cloud")
class PlatformAdminTest {

    private static final String ADMIN_PASSWORD = "a-long-enough-staff-password";
    private static final String OWNER_PASSWORD = "correct-horse-battery";

    @LocalServerPort int port;

    @Autowired PlatformAdminService admins;
    @Autowired PlatformSessionService platformSessions;
    @Autowired TenantCredentialService tillCredentials;
    @Autowired LicenceService licences;
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

    // ------------------------------------------------------------------ the blocker M4-08 exists for

    /**
     * The whole point of the milestone: before this endpoint, an owner account could be created only
     * by running Java, so no real shop could sign in.
     */
    @Test
    void staffSignAShopUpAndItsOwnerCanImmediatelySignIn() {
        String staff = signInAsStaff("estate1@lumora.lk");

        ResponseEntity<JsonNode> created =
                post(
                        "/api/platform/tenants",
                        staff,
                        """
                        {"name":"Kandy Stores","ownerEmail":"owner-new1@kandystores.lk",
                         "ownerPassword":"%s","ownerName":"Nimal","terminalLabel":"Till 1"}
                        """
                                .formatted(OWNER_PASSWORD));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody().get("tillToken").asText()).startsWith("lum_");

        // The owner can sign in to the console — the thing that was impossible before M4-08.
        ResponseEntity<JsonNode> login =
                postAnonymous(
                        "/api/console/auth/login",
                        """
                        {"email":"owner-new1@kandystores.lk","password":"%s"}
                        """
                                .formatted(OWNER_PASSWORD));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().get("token").asText()).startsWith("lums_");
    }

    /** And the till token minted alongside it works against ingest straight away. */
    @Test
    void theTillTokenHandedBackAuthenticatesTheSyncEndpoint() {
        String staff = signInAsStaff("estate2@lumora.lk");
        String tillToken =
                post(
                                "/api/platform/tenants",
                                staff,
                                """
                                {"name":"Galle Stores","ownerEmail":"owner-new2@gallestores.lk",
                                 "ownerPassword":"%s","ownerName":"Kamala"}
                                """
                                        .formatted(OWNER_PASSWORD))
                        .getBody()
                        .get("tillToken")
                        .asText();

        // What is under test is that the credential opens the door, not what it carries through.
        ResponseEntity<String> pushed = knockOnIngest(tillToken);
        assertThat(pushed.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Four things have to exist for a shop to work, and a half-made one is worse than none: it looks
     * created and silently syncs nothing.
     */
    @Test
    void creatingAShopIsAllOrNothing() {
        String staff = signInAsStaff("estate3@lumora.lk");

        // A password below the console minimum fails inside the same transaction as the tenant row.
        ResponseEntity<JsonNode> rejected =
                post(
                        "/api/platform/tenants",
                        staff,
                        """
                        {"name":"Half Made Stores","ownerEmail":"owner-new3@halfmade.lk",
                         "ownerPassword":"short","ownerName":"Sunil"}
                        """);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(count("SELECT count(*) FROM tenants WHERE name = 'Half Made Stores'")).isZero();
    }

    // ------------------------------------------------------------------ the wall between kinds

    /**
     * Administering a business is not the same power as operating one. Staff can create a shop and
     * cannot ring up a sale in it.
     */
    @Test
    void aPlatformSessionCannotPushSales() {
        String staff = signInAsStaff("estate4@lumora.lk");

        ResponseEntity<String> pushed = knockOnIngest(staff);

        // 403, not 401: the credential is perfectly valid and is simply not what /api/sync serves.
        assertThat(pushed.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Nor read a shop's takings through the owner's endpoints. */
    @Test
    void aPlatformSessionCannotReadTheConsoleReports() {
        String staff = signInAsStaff("estate5@lumora.lk");

        assertThat(get("/api/console/today", staff).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** And the reverse: an owner cannot reach the estate. */
    @Test
    void aConsoleSessionCannotReachThePlatformApi() {
        String staff = signInAsStaff("estate6@lumora.lk");
        post(
                "/api/platform/tenants",
                staff,
                """
                {"name":"Matara Stores","ownerEmail":"owner-new6@matarastores.lk",
                 "ownerPassword":"%s","ownerName":"Ranjith"}
                """
                        .formatted(OWNER_PASSWORD));

        String ownerToken =
                postAnonymous(
                                "/api/console/auth/login",
                                """
                                {"email":"owner-new6@matarastores.lk","password":"%s"}
                                """
                                        .formatted(OWNER_PASSWORD))
                        .getBody()
                        .get("token")
                        .asText();

        assertThat(get("/api/platform/tenants", ownerToken).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** A till's machine token is not a way in either. */
    @Test
    void aTillTokenCannotReachThePlatformApi() {
        var provisioned = tillCredentials.provision("Badulla Stores", "Till 1");

        assertThat(get("/api/platform/tenants", provisioned.token()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theEstateIsClosedToAnUnauthenticatedCaller() {
        assertThat(get("/api/platform/tenants", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The guard that makes the third kind safe. A platform principal carries no tenant, and asking
     * for one has to fail loudly — the quiet version of this bug reads the wrong shop and answers
     * 200.
     */
    @Test
    void aPlatformPrincipalRefusesToNameATenant() {
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.ofPlatform(1L, "staff@lumora.lk");

        assertThatThrownBy(principal::tenantId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not scoped to a tenant");
    }

    // ------------------------------------------------------------------ licences

    /**
     * The commercial lever, and the architecture's own promise at the same time: an unlicensed shop
     * stops reaching the cloud and does not stop selling.
     */
    @Test
    void aLapsedLicenceStopsIngest() {
        var provisioned = tillCredentials.provision("Lapsing Stores", "Till 1");
        assertThat(knockOnIngest(provisioned.token()).getStatusCode()).isEqualTo(HttpStatus.OK);

        expireEveryLicenceOf(provisioned.tenantId());

        assertThat(knockOnIngest(provisioned.token()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The deliberate asymmetry from V209: a lapse must not lock an owner out of their own takings,
     * because the console is the only place that can tell them what is wrong.
     */
    @Test
    void aLapsedLicenceLeavesTheOwnerReadingTheConsole() {
        String staff = signInAsStaff("estate7@lumora.lk");
        long tenantId =
                post(
                                "/api/platform/tenants",
                                staff,
                                """
                                {"name":"Lapsed But Readable","ownerEmail":"owner-new7@lapsed.lk",
                                 "ownerPassword":"%s","ownerName":"Chandra"}
                                """
                                        .formatted(OWNER_PASSWORD))
                        .getBody()
                        .get("tenantId")
                        .asLong();

        String ownerToken =
                postAnonymous(
                                "/api/console/auth/login",
                                """
                                {"email":"owner-new7@lapsed.lk","password":"%s"}
                                """
                                        .formatted(OWNER_PASSWORD))
                        .getBody()
                        .get("token")
                        .asText();

        expireEveryLicenceOf(tenantId);

        ResponseEntity<JsonNode> me = get("/api/console/auth/me", ownerToken);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        // And it says so, rather than leaving the owner to infer a lapse from figures that stopped.
        assertThat(me.getBody().get("licensed").asBoolean()).isFalse();
    }

    /** Suspension is the blunt instrument, and it does stop both. */
    @Test
    void suspendingAShopStopsTheOwnerToo() {
        String staff = signInAsStaff("estate8@lumora.lk");
        long tenantId =
                post(
                                "/api/platform/tenants",
                                staff,
                                """
                                {"name":"Suspended Stores","ownerEmail":"owner-new8@suspended.lk",
                                 "ownerPassword":"%s","ownerName":"Priya"}
                                """
                                        .formatted(OWNER_PASSWORD))
                        .getBody()
                        .get("tenantId")
                        .asLong();

        String ownerToken =
                postAnonymous(
                                "/api/console/auth/login",
                                """
                                {"email":"owner-new8@suspended.lk","password":"%s"}
                                """
                                        .formatted(OWNER_PASSWORD))
                        .getBody()
                        .get("token")
                        .asText();
        assertThat(get("/api/console/auth/me", ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        post("/api/platform/tenants/" + tenantId + "/suspend", staff, """
                {"why":"unpaid"}""");

        // The live session dies immediately rather than when it happens to expire — a seven-day
        // console token would otherwise make a suspension take up to a week.
        assertThat(get("/api/console/auth/me", ownerToken).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Renewing early adds to what is left rather than throwing away the rest of a paid month. */
    @Test
    void renewingEarlyExtendsRatherThanRestarts() {
        String staff = signInAsStaff("estate9@lumora.lk");
        long tenantId =
                post(
                                "/api/platform/tenants",
                                staff,
                                """
                                {"name":"Renewing Stores","ownerEmail":"owner-new9@renewing.lk",
                                 "ownerPassword":"%s","ownerName":"Asela","licenceDays":30}
                                """
                                        .formatted(OWNER_PASSWORD))
                        .getBody()
                        .get("tenantId")
                        .asLong();

        Instant before = licences.current(tenantId).orElseThrow().expiresAt();

        post(
                "/api/platform/tenants/" + tenantId + "/licence",
                staff,
                """
                {"planCode":"standard","days":30,"note":"renewal"}""");

        // The new period starts where the old one ends, so the shop has roughly sixty days, not
        // thirty. Compared loosely because both ends are computed from now() on two clocks.
        Instant latest =
                licences.history(tenantId).stream()
                        .map(LicenceService.Licence::expiresAt)
                        .max(Instant::compareTo)
                        .orElseThrow();
        assertThat(latest).isAfter(before.plus(25, ChronoUnit.DAYS));
    }

    /** A licence period is appended, never overwritten — the history is the billing record. */
    @Test
    void licencesAreAppendOnly() {
        String staff = signInAsStaff("estate10@lumora.lk");
        long tenantId =
                post(
                                "/api/platform/tenants",
                                staff,
                                """
                                {"name":"History Stores","ownerEmail":"owner-new10@history.lk",
                                 "ownerPassword":"%s","ownerName":"Dilani"}
                                """
                                        .formatted(OWNER_PASSWORD))
                        .getBody()
                        .get("tenantId")
                        .asLong();

        post("/api/platform/tenants/" + tenantId + "/licence", staff, """
                {"planCode":"plus","days":30}""");
        post("/api/platform/tenants/" + tenantId + "/licence", staff, """
                {"planCode":"plus","days":30}""");

        assertThat(licences.history(tenantId)).hasSize(3);
    }

    @Test
    void anUnknownPlanCodeIsRejectedRatherThanIgnored() {
        assertThatThrownBy(() -> licences.planIdForCode("no-such-plan"))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("no-such-plan");
    }

    // ------------------------------------------------------------------ feature flags

    /** A tenant's capabilities are its plan's, with its own overrides on top. */
    @Test
    void anOverrideCanAddAndTakeAway() {
        String staff = signInAsStaff("estate11@lumora.lk");
        long tenantId =
                post(
                                "/api/platform/tenants",
                                staff,
                                """
                                {"name":"Flagged Stores","ownerEmail":"owner-new11@flagged.lk",
                                 "ownerPassword":"%s","ownerName":"Mala","planCode":"standard"}
                                """
                                        .formatted(OWNER_PASSWORD))
                        .getBody()
                        .get("tenantId")
                        .asLong();

        // Standard does not include stocktake.
        assertThat(licences.effectiveFlags(tenantId)).doesNotContain("stocktake");

        post(
                "/api/platform/tenants/" + tenantId + "/flags",
                staff,
                """
                {"flagCode":"stocktake","enabled":true,"note":"pilot"}""");
        assertThat(licences.effectiveFlags(tenantId)).contains("stocktake");

        // And an override can subtract, which is why it is a boolean and not a row's presence.
        post(
                "/api/platform/tenants/" + tenantId + "/flags",
                staff,
                """
                {"flagCode":"back_office","enabled":false,"note":"withdrawn"}""");
        assertThat(licences.effectiveFlags(tenantId)).doesNotContain("back_office");
    }

    /** An unlicensed shop has no capabilities, rather than the last plan's left lying around. */
    @Test
    void aLapsedLicenceLeavesNoFlagsBehind() {
        var provisioned = tillCredentials.provision("Flagless Stores", "Till 1");
        assertThat(licences.effectiveFlags(provisioned.tenantId())).isNotEmpty();

        expireEveryLicenceOf(provisioned.tenantId());

        assertThat(licences.effectiveFlags(provisioned.tenantId())).isEmpty();
    }

    /** The registry is a table so that a typo fails here, not silently forever. */
    @Test
    void anUnknownFlagCodeIsRefusedByTheDatabase() {
        String staff = signInAsStaff("estate12@lumora.lk");
        long tenantId =
                post(
                                "/api/platform/tenants",
                                staff,
                                """
                                {"name":"Typo Stores","ownerEmail":"owner-new12@typo.lk",
                                 "ownerPassword":"%s","ownerName":"Sarath"}
                                """
                                        .formatted(OWNER_PASSWORD))
                        .getBody()
                        .get("tenantId")
                        .asLong();

        // 'stock_take' is not 'stocktake'. Without the foreign key this would be a flag that is off
        // forever and reports nothing.
        assertThat(
                        post(
                                        "/api/platform/tenants/" + tenantId + "/flags",
                                        staff,
                                        """
                                        {"flagCode":"stock_take","enabled":true}""")
                                .getStatusCode()
                                .is2xxSuccessful())
                .isFalse();
    }

    // ------------------------------------------------------------------ credentials and audit

    @Test
    void aRevokedTokenStopsSyncingAndTheShopKeepsItsHistory() {
        String staff = signInAsStaff("estate13@lumora.lk");
        JsonNode created =
                post(
                                "/api/platform/tenants",
                                staff,
                                """
                                {"name":"Rekeyed Stores","ownerEmail":"owner-new13@rekeyed.lk",
                                 "ownerPassword":"%s","ownerName":"Tharindu"}
                                """
                                        .formatted(OWNER_PASSWORD))
                        .getBody();
        long tenantId = created.get("tenantId").asLong();
        String firstToken = created.get("tillToken").asText();

        long credentialId =
                jdbc.queryForObject(
                        "SELECT id FROM tenant_api_credentials WHERE tenant_id = ?",
                        Long.class,
                        tenantId);
        post(
                "/api/platform/tenants/" + tenantId + "/credentials/" + credentialId + "/revoke",
                staff,
                "{}");

        assertThat(knockOnIngest(firstToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // A replacement key works, which is the reason credentials are a table and not a column.
        String replacement =
                post(
                                "/api/platform/tenants/" + tenantId + "/credentials",
                                staff,
                                """
                                {"label":"Till 1 (replacement)"}""")
                        .getBody()
                        .get("token")
                        .asText();
        assertThat(knockOnIngest(replacement).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** A credential id from another shop must not be revocable through this shop's path. */
    @Test
    void aCredentialCannotBeRevokedThroughAnotherShopsPath() {
        String staff = signInAsStaff("estate14@lumora.lk");
        var mine = tillCredentials.provision("Mine Stores", "Till 1");
        var theirs = tillCredentials.provision("Theirs Stores", "Till 1");

        long theirCredentialId =
                jdbc.queryForObject(
                        "SELECT id FROM tenant_api_credentials WHERE tenant_id = ?",
                        Long.class,
                        theirs.tenantId());

        ResponseEntity<JsonNode> response =
                post(
                        "/api/platform/tenants/"
                                + mine.tenantId()
                                + "/credentials/"
                                + theirCredentialId
                                + "/revoke",
                        staff,
                        "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(knockOnIngest(theirs.token()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Acts that reach across shops leave a record naming who did them. */
    @Test
    void everyAdministrativeWriteIsAudited() {
        String staff = signInAsStaff("estate15@lumora.lk");
        long tenantId =
                post(
                                "/api/platform/tenants",
                                staff,
                                """
                                {"name":"Audited Stores","ownerEmail":"owner-new15@audited.lk",
                                 "ownerPassword":"%s","ownerName":"Ishara"}
                                """
                                        .formatted(OWNER_PASSWORD))
                        .getBody()
                        .get("tenantId")
                        .asLong();
        post("/api/platform/tenants/" + tenantId + "/suspend", staff, """
                {"why":"testing"}""");

        JsonNode trail = get("/api/platform/audit?limit=50", staff).getBody();

        assertThat(trail.toString()).contains("tenant.create").contains("tenant.suspend");
        // Named, not anonymous. An action nobody can be attributed to is one nobody can be asked
        // about — the argument M3-08 made for the shop floor, one level up.
        assertThat(trail.toString()).contains("estate15@lumora.lk");
    }

    /** The audit row shares the transaction, so a rolled-back act records nothing. */
    @Test
    void aFailedActLeavesNoAuditRow() {
        String staff = signInAsStaff("estate16@lumora.lk");
        long before = count("SELECT count(*) FROM platform_audit");

        post(
                "/api/platform/tenants",
                staff,
                """
                {"name":"Never Made Stores","ownerEmail":"owner-new16@nevermade.lk",
                 "ownerPassword":"tooshort","ownerName":"Nadeeka"}
                """);

        assertThat(count("SELECT count(*) FROM platform_audit")).isEqualTo(before);
    }

    // ------------------------------------------------------------------ staff accounts

    @Test
    void aDeactivatedAdminsSessionDiesOnItsNextRequest() {
        String staff = signInAsStaff("estate17@lumora.lk");
        assertThat(get("/api/platform/tenants", staff).getStatusCode()).isEqualTo(HttpStatus.OK);

        jdbc.update("UPDATE platform_admins SET active = false WHERE email = ?", "estate17@lumora.lk");

        assertThat(get("/api/platform/tenants", staff).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void changingAPasswordEndsEverySessionIncludingThisOne() {
        String email = "estate18@lumora.lk";
        String staff = signInAsStaff(email);
        String second = platformSessions.signIn(email, ADMIN_PASSWORD).orElseThrow().token();

        ResponseEntity<JsonNode> changed =
                post(
                        "/api/platform/auth/password",
                        staff,
                        """
                        {"currentPassword":"%s","newPassword":"an-even-longer-new-password"}
                        """
                                .formatted(ADMIN_PASSWORD));
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(get("/api/platform/tenants", staff).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/platform/tenants", second).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theCurrentPasswordIsRequiredToChangeIt() {
        String staff = signInAsStaff("estate19@lumora.lk");

        assertThat(
                        post(
                                        "/api/platform/auth/password",
                                        staff,
                                        """
                                        {"currentPassword":"not it","newPassword":"a-perfectly-long-password"}""")
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /** Staff passwords are held to a longer minimum than owners', because they are worth more. */
    @Test
    void aShortStaffPasswordIsRefused() {
        assertThatThrownBy(() -> admins.create("tooshort@lumora.lk", "short-ish-12", "Somebody"))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("16");
    }

    /** An unknown email and a wrong password must be indistinguishable from outside. */
    @Test
    void aFailedStaffLoginSaysNothingAboutWhichHalfWasWrong() {
        signInAsStaff("estate20@lumora.lk");

        ResponseEntity<JsonNode> wrongPassword =
                postAnonymous(
                        "/api/platform/auth/login",
                        """
                        {"email":"estate20@lumora.lk","password":"not the password"}""");
        ResponseEntity<JsonNode> unknownEmail =
                postAnonymous(
                        "/api/platform/auth/login",
                        """
                        {"email":"nobody-at-all@lumora.lk","password":"not the password"}""");

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody().get("detail").asText())
                .isEqualTo(unknownEmail.getBody().get("detail").asText());
    }

    // ------------------------------------------------------------------ the estate view

    @Test
    void theEstateListShowsPlanLicenceAndState() {
        String staff = signInAsStaff("estate21@lumora.lk");
        post(
                "/api/platform/tenants",
                staff,
                """
                {"name":"Listed Stores","ownerEmail":"owner-new21@listed.lk",
                 "ownerPassword":"%s","ownerName":"Roshan","planCode":"plus"}
                """
                        .formatted(OWNER_PASSWORD));

        JsonNode estate = get("/api/platform/tenants", staff).getBody();

        JsonNode row = null;
        for (JsonNode candidate : estate) {
            if ("Listed Stores".equals(candidate.get("name").asText())) {
                row = candidate;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("planCode").asText()).isEqualTo("plus");
        assertThat(row.get("state").asText()).isEqualTo("LIVE");
        assertThat(row.get("terminalCount").asInt()).isEqualTo(1);
        assertThat(row.get("ownerCount").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private String signInAsStaff(String email) {
        admins.create(email, ADMIN_PASSWORD, "Estate Staff");
        return platformSessions.signIn(email, ADMIN_PASSWORD).orElseThrow().token();
    }

    /**
     * Moves every licence this shop holds into the past.
     *
     * <p>An UPDATE, which nothing in production does — the table is append-only there. A test needs
     * a lapse without waiting thirty days for one, and forcing the clock is the honest way to say
     * so rather than adding a back door to {@link LicenceService} that only tests use.
     */
    private void expireEveryLicenceOf(long tenantId) {
        jdbc.update(
                """
                UPDATE tenant_licences
                   SET starts_at = now() - interval '60 days', expires_at = now() - interval '1 day'
                 WHERE tenant_id = ?
                """,
                tenantId);
    }

    private ResponseEntity<JsonNode> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(
                url(path), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String path, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(
                url(path), HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> postAnonymous(String path, String body) {
        return post(path, null, body);
    }

    /**
     * Knocks on the ingest door and reports only what the door said.
     *
     * <p>The batch carries one item of an aggregate kind the cloud does not know. That is
     * deliberate: {@code SyncBatch.items} is {@code @NotEmpty}, so a genuinely empty batch would be
     * rejected by bean validation at 400 before authentication was ever consulted — and these tests
     * are about the credential, not the cargo. An unknown kind is refused per row inside a 200, so
     * the status is the auth outcome and nothing else. See {@code SyncController} on why ingest is
     * always 200 for an authenticated caller.
     */
    private ResponseEntity<String> knockOnIngest(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        String body =
                """
                {"items":[{"aggregate":"not_a_real_aggregate","aggregateId":"%s","payload":{}}]}"""
                        .formatted(UUID.randomUUID());
        return http.exchange(
                url("/api/sync/batch"), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private long count(String sql, Object... args) {
        Long found = jdbc.queryForObject(sql, Long.class, args);
        return found == null ? 0 : found;
    }
}

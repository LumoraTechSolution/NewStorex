package com.lumora.pos.backup;

import static org.assertj.core.api.Assertions.assertThat;

import com.lumora.pos.cloud.TenantCredentialService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;
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
 * A shop's whole database, off the shop's disk (M5-06).
 *
 * <p>Driven over HTTP rather than against the service, because half of what this task is are the
 * things between the two: the auth filter deciding which shop the bytes belong to, the headers a
 * till has to get right, and a rejection arriving as a rejection rather than as a reset
 * connection. A service-level test would pass with the controller unmounted.
 *
 * <p>The store is a real filesystem under {@code target/}, not a mock. Every interesting assertion
 * here is about what is <em>on</em> storage after something went wrong — an archive that failed
 * its digest must be gone, and a mock would happily report a delete that a real store botched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"cloud", "test"})
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:5444/lumora_test_cloud",
            "lumora.backup.directory=target/test-cloud-backups",
            // Three rather than the deployed fourteen, so the retention test does not have to
            // upload fifteen archives to prove one thing.
            "lumora.backup.keep-per-tenant=3",
            "lumora.backup.max-bytes=4096"
        })
class CloudBackupTest {

    private static final Path STORE = Path.of("target/test-cloud-backups");

    @LocalServerPort int port;

    @Autowired TenantCredentialService tenants;
    @Autowired JdbcTemplate jdbc;

    private RestTemplate http;
    private String myToken;
    private long mine;

    @BeforeEach
    void aShopAndAnEmptyStore() throws IOException {
        // Same client as TenantIsolationTest, for the same reason: the legacy transport turns a
        // 401 on a request with a body into an exception instead of a response, and a 401 here is
        // an assertion rather than a failure.
        http = new RestTemplate(new JdkClientHttpRequestFactory());
        http.setErrorHandler(
                new DefaultResponseErrorHandler() {
                    @Override
                    public boolean hasError(org.springframework.http.client.ClientHttpResponse r) {
                        return false;
                    }
                });

        TenantCredentialService.Provisioned provisioned = tenants.provision("Kandy Stores", "Till 1");
        mine = provisioned.tenantId();
        myToken = provisioned.token();

        if (Files.exists(STORE)) {
            try (Stream<Path> walk = Files.walk(STORE)) {
                walk.sorted(Comparator.reverseOrder()).forEach(CloudBackupTest::deleteQuietly);
            }
        }
    }

    // ------------------------------------------------------------------ the ordinary path

    @Test
    void anArchiveIsStoredAndRecorded() {
        byte[] archive = "a shop's whole history".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = upload(myToken, "T1", "storex-2026-09-01T0314.dump", archive);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"alreadyHeld\":false");

        // The row and the object, because either one alone is a backup that does not exist.
        assertThat(rows(mine)).isEqualTo(1);
        assertThat(objectFor(mine, "T1", "storex-2026-09-01T0314.dump")).exists().hasBinaryContent(archive);
    }

    @Test
    void theDigestRecordedIsTheOneComputedFromWhatArrived() {
        byte[] archive = "bytes that must hash to something".getBytes(StandardCharsets.UTF_8);

        upload(myToken, "T1", "storex-2026-09-01T0314.dump", archive);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT sha256 FROM tenant_backups WHERE tenant_id = ?", String.class, mine))
                .isEqualTo(sha256(archive));
    }

    /**
     * The retry case, and the reason the table has a unique index rather than a timestamp.
     *
     * <p>A till whose upload timed out after the cloud had already written cannot tell the
     * difference from one that never arrived, so it sends again. Twice must mean once.
     */
    @Test
    void thesameArchiveTwiceIsHeldOnce() {
        byte[] archive = "sent twice".getBytes(StandardCharsets.UTF_8);

        upload(myToken, "T1", "storex-2026-09-01T0314.dump", archive);
        ResponseEntity<String> second = upload(myToken, "T1", "storex-2026-09-01T0314.dump", archive);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).contains("\"alreadyHeld\":true");
        assertThat(rows(mine)).isEqualTo(1);
    }

    @Test
    void twoTillsInOneShopDoNotOverwriteEachOther() {
        upload(myToken, "T1", "storex-2026-09-01T0314.dump", "till one".getBytes(StandardCharsets.UTF_8));
        upload(myToken, "T2", "storex-2026-09-01T0314.dump", "till two".getBytes(StandardCharsets.UTF_8));

        assertThat(rows(mine)).isEqualTo(2);
        assertThat(objectFor(mine, "T1", "storex-2026-09-01T0314.dump"))
                .hasBinaryContent("till one".getBytes(StandardCharsets.UTF_8));
        assertThat(objectFor(mine, "T2", "storex-2026-09-01T0314.dump"))
                .hasBinaryContent("till two".getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ what must be refused

    @Test
    void anUploadWithNoTokenIsRejectedAndStoresNothing() {
        ResponseEntity<String> response =
                upload(null, "T1", "storex-2026-09-01T0314.dump", "anyone at all".getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rows(mine)).isZero();
        assertThat(STORE.resolve("tenant-" + mine)).doesNotExist();
    }

    /**
     * The one that would be a remote write to anywhere on the host's disk.
     *
     * <p>The name becomes part of an object key, so it is matched against the till's own naming
     * and nothing else. This is not a hypothetical: the till puts the name in a header, and a
     * header is whatever the machine sending it says it is.
     */
    @Test
    void aNameThatIsNotAStoreXArchiveIsRefused() {
        for (String hostile :
                new String[] {
                    "../../etc/passwd",
                    "..%2F..%2Fescape.dump",
                    "storex-2026-09-01T0314.dump/../../elsewhere.dump",
                    "shop.dump",
                    ""
                }) {
            ResponseEntity<String> response = upload(myToken, "T1", hostile, "x".getBytes(StandardCharsets.UTF_8));

            assertThat(response.getStatusCode())
                    .as("name %s", hostile)
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        assertThat(rows(mine)).isZero();
    }

    @Test
    void aTerminalCodeThatCouldBeAPathIsRefused() {
        ResponseEntity<String> response =
                upload(myToken, "../other", "storex-2026-09-01T0314.dump", "x".getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rows(mine)).isZero();
    }

    /**
     * A truncated or altered upload must leave nothing behind.
     *
     * <p>Keeping it would put an archive nobody can vouch for in the same folder as ones they can,
     * which is the exact failure this whole feature exists to prevent — a backup that looks fine
     * until the morning somebody needs it.
     */
    @Test
    void anArchiveThatDoesNotMatchItsDigestIsRejectedAndDeleted() {
        byte[] archive = "what actually arrived".getBytes(StandardCharsets.UTF_8);
        String wrong = sha256("what the till claimed it sent".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response =
                upload(myToken, "T1", "storex-2026-09-01T0314.dump", archive, wrong);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rows(mine)).isZero();
        assertThat(objectFor(mine, "T1", "storex-2026-09-01T0314.dump")).doesNotExist();
    }

    @Test
    void anArchiveOverTheSizeLimitIsRefused() {
        byte[] tooBig = new byte[5000]; // the test profile caps at 4096

        ResponseEntity<String> response = upload(myToken, "T1", "storex-2026-09-01T0314.dump", tooBig);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rows(mine)).isZero();
    }

    // ------------------------------------------------------------------ retention

    @Test
    void onlyTheNewestArchivesSurvive() {
        for (String day : new String[] {"01", "02", "03", "04", "05"}) {
            upload(myToken, "T1", "storex-2026-09-%sT0314.dump".formatted(day), ("day " + day).getBytes(StandardCharsets.UTF_8));
        }

        assertThat(rows(mine)).isEqualTo(3);
        assertThat(
                        jdbc.queryForList(
                                "SELECT name FROM tenant_backups WHERE tenant_id = ? ORDER BY taken_at",
                                String.class,
                                mine))
                .containsExactly(
                        "storex-2026-09-03T0314.dump",
                        "storex-2026-09-04T0314.dump",
                        "storex-2026-09-05T0314.dump");

        // The objects go with the rows. Retention that only deleted rows would keep paying for
        // every archive a shop ever took, silently.
        assertThat(objectFor(mine, "T1", "storex-2026-09-01T0314.dump")).doesNotExist();
        assertThat(objectFor(mine, "T1", "storex-2026-09-05T0314.dump")).exists();
    }

    // ------------------------------------------------------------------ the isolation assertion

    /**
     * The assertion this class exists for, alongside the digest one.
     *
     * <p>A backup is a shop's entire database. A key derived from anything the caller sent, rather
     * than from the authenticated tenant, would put one shopkeeper's customers, staff and takings
     * where another one can ask for them.
     */
    @Test
    void anArchiveIsFiledUnderTheTenantOfTheTokenAndNothingElse() {
        TenantCredentialService.Provisioned other = tenants.provision("Galle Stores", "Till 1");

        upload(other.token(), "T1", "storex-2026-09-01T0314.dump", "galle".getBytes(StandardCharsets.UTF_8));

        assertThat(rows(mine)).isZero();
        assertThat(rows(other.tenantId())).isEqualTo(1);
        assertThat(objectFor(other.tenantId(), "T1", "storex-2026-09-01T0314.dump")).exists();
        assertThat(STORE.resolve("tenant-" + mine)).doesNotExist();
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<String> upload(String token, String terminal, String name, byte[] body) {
        return upload(token, terminal, name, body, sha256(body));
    }

    private ResponseEntity<String> upload(
            String token, String terminal, String name, byte[] body, String sha256) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(body.length);
        if (token != null) headers.setBearerAuth(token);
        headers.set("X-Backup-Terminal", terminal);
        headers.set("X-Backup-Name", name);
        headers.set("X-Backup-Taken-At", Instant.parse("2026-09-01T03:14:00Z").toString());
        headers.set("X-Backup-Sha256", sha256);

        return http.exchange(
                "http://127.0.0.1:" + port + "/api/sync/backup",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
    }

    private int rows(long tenantId) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM tenant_backups WHERE tenant_id = ?", Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    private Path objectFor(long tenantId, String terminal, String name) {
        return STORE.resolve("tenant-" + tenantId).resolve(terminal).resolve(name);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover from a previous run that Windows still has open would fail the cleanup
            // and not the test; every assertion here names its own file.
        }
    }
}

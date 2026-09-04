package com.lumora.pos.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Checking a cloud token before the wizard saves it (M5-03).
 *
 * <h2>Written after the failure it prevents</h2>
 *
 * A till was set up with a token the cloud rejects and an empty cloud address. The wizard said
 * "Saved.", the shop sold perfectly, and 62 outbox rows queued up going nowhere — found by reading
 * the database, which is not something a shopkeeper does. That is precisely the silent failure the
 * wizard replaced {@code setx /M} to avoid, and it survived because the only check was that the
 * field was non-empty. Non-empty is not a credential.
 *
 * <h2>A real HTTP server, not a mocked client</h2>
 *
 * The behaviour under test is entirely about what happens on the wire: a 401 must block, a refused
 * connection must not, and a 200 must carry the shop's name back. A mocked {@code RestClient} would
 * assert that the code branches on values a mock chose, which is the part that was never in doubt.
 * The JDK's own {@code HttpServer} costs nothing and answers for real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class CloudCredentialCheckTest {

    @Autowired CloudCredentialCheck check;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Starts a stand-in cloud on a free port and returns its base URL. */
    private String cloudAnswering(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/sync/entitlement",
                exchange -> {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ----------------------------------------------------------------- the good case

    /**
     * A working token comes back with the shop's name, which is the answer worth showing.
     *
     * <p>A green tick would confirm only that something answered. The name is what catches the
     * mistake that actually happens: a valid token belonging to a different shop.
     */
    @Test
    void aGoodTokenIsAcceptedAndNamesTheShop() throws IOException {
        String url = cloudAnswering(200, "{\"licensed\":true,\"tenantName\":\"jeewa stores\"}");

        CloudCredentialCheck.Result result = check.check(url, "lum_good");

        assertThat(result.ok()).isTrue();
        assertThat(result.reachable()).isTrue();
        assertThat(result.shopName()).isEqualTo("jeewa stores");
    }

    /**
     * An older cloud that sends no name is still a working token.
     *
     * <p>{@code tenantName} arrived with V122. A till set up against a cloud deployed before it
     * must not be told its perfectly good credential is bad — that would make an upgrade ordering
     * problem look like a security one.
     */
    @Test
    void aCloudThatSendsNoNameStillCountsAsWorking() throws IOException {
        String url = cloudAnswering(200, "{\"licensed\":true}");

        CloudCredentialCheck.Result result = check.check(url, "lum_good");

        assertThat(result.ok()).isTrue();
        assertThat(result.shopName()).isNull();
    }

    // ----------------------------------------------------------------- the case that bit us

    /**
     * A 401 is the one answer that blocks setup.
     *
     * <p>The cloud looked the token up and does not know it — revoked, mistyped, or belonging to a
     * tenant that has been deleted. Every one of those is fixable in the minute the person is still
     * on the screen, and unfixable once they have finished and moved on.
     */
    @Test
    void aTokenTheCloudRejectsIsReportedAsRejectedRatherThanSaved() throws IOException {
        String url = cloudAnswering(401, "{\"title\":\"Unauthorized\"}");

        CloudCredentialCheck.Result result = check.check(url, "lum_revoked");

        assertThat(result.ok()).isFalse();
        // Reachable: we got a real verdict, which is what makes this worth stopping for.
        assertThat(result.reachable()).isTrue();
        assertThat(result.detail()).contains("does not recognise");
    }

    // ----------------------------------------------------------------- the cases that must not block

    /**
     * An unreachable cloud is not a verdict on the token.
     *
     * <p>§A: the network is on the critical path of nothing. A shop being set up in a back room
     * with no signal is being set up correctly, and refusing to finish would make this wizard worse
     * than the manual procedure it replaced.
     */
    @Test
    void anUnreachableCloudDoesNotBlockSetup() {
        // Nothing is listening here. A port in the ephemeral range, deliberately never bound.
        CloudCredentialCheck.Result result = check.check("http://127.0.0.1:59999", "lum_unknown");

        assertThat(result.ok()).isFalse();
        assertThat(result.reachable()).isFalse();
    }

    /**
     * A token with no address is caught before it produces a confusing error.
     *
     * <p>This is the exact shape of the incident: with no address the till falls back to
     * {@code http://127.0.0.1:8082} — itself — and reports a connection failure about a cloud the
     * operator can plainly see running. Saying "a cloud address is needed" is the truth.
     */
    @Test
    void aTokenWithNoAddressIsRefusedWithAnHonestReason() {
        CloudCredentialCheck.Result result = check.check("", "lum_something");

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("cloud address");
    }

    /** Nothing to check, and nothing to complain about — the cloud step is skippable. */
    @Test
    void noTokenIsNotAnError() {
        CloudCredentialCheck.Result result = check.check("https://example.invalid", "");

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("No token");
    }

    /**
     * An address that answers but is not this API reads as unreachable, not as a bad token.
     *
     * <p>Somebody pasting their console's URL instead of the API's has a wrong-address problem, and
     * telling them their token is bad would send them to reissue a credential that was fine.
     */
    @Test
    void anAddressThatIsNotTheApiIsNotTreatedAsABadToken() throws IOException {
        String url = cloudAnswering(200, "");

        CloudCredentialCheck.Result result = check.check(url, "lum_good");

        assertThat(result.ok()).isFalse();
        assertThat(result.reachable()).isFalse();
    }
}

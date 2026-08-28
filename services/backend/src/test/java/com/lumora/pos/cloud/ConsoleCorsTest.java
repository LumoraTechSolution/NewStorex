package com.lumora.pos.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * The browser handshake the console depends on (M4-05).
 *
 * <p>This class exists because of a real failure. The console's endpoints were verified with curl,
 * which does not implement CORS — so every request passed, and the first attempt to use the app in
 * an actual browser reported "Failed to fetch" with nothing in the server log to explain it. Two
 * things were wrong at once and each would have been enough on its own: there was no CORS policy,
 * and the auth filter answered 401 to preflights, which by design carry no credential.
 *
 * <p>A test that speaks HTTP is not the same as a test that speaks browser. These assert the
 * handshake explicitly, because nothing else in the suite can see it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"cloud", "test"})
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:5444/lumora_test_cloud",
            "lumora.console.allowed-origins=http://localhost:3001"
        })
class ConsoleCorsTest {

    private static final String CONSOLE_ORIGIN = "http://localhost:3001";

    @LocalServerPort int port;

    private RestTemplate http;

    @BeforeEach
    void client() {
        http = new RestTemplate(new JdkClientHttpRequestFactory());
        http.setErrorHandler(
                new DefaultResponseErrorHandler() {
                    @Override
                    public boolean hasError(org.springframework.http.client.ClientHttpResponse r) {
                        return false;
                    }
                });
    }

    /** The bug that broke the console: a preflight has no credential and must not need one. */
    @Test
    void aPreflightIsAnsweredWithoutACredential() {
        ResponseEntity<String> response = preflight("/api/console/today", "GET");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(CONSOLE_ORIGIN);
    }

    @Test
    void aPreflightAllowsTheAuthorizationHeaderTheRealRequestWillSend() {
        ResponseEntity<String> response = preflight("/api/console/today", "GET");

        assertThat(response.getHeaders().getAccessControlAllowHeaders())
                .anySatisfy(h -> assertThat(h).containsIgnoringCase("authorization"));
    }

    @Test
    void theLoginPreflightIsAnsweredToo() {
        // Login is exempt from authentication, but it still needs a preflight because the console
        // posts JSON — and a Content-Type of application/json is enough on its own to trigger one.
        ResponseEntity<String> response = preflight("/api/console/auth/login", "POST");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * A 401 needs CORS headers as much as a 200 does.
     *
     * <p>Without them the browser hides the response entirely and the console shows "Failed to
     * fetch" instead of "your session ended" — which is the difference between a user who signs in
     * again and one who reports that the app is broken.
     */
    @Test
    void anUnauthorizedResponseStillCarriesTheCorsHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(CONSOLE_ORIGIN);
        headers.setBearerAuth("lums_not-a-real-session");

        ResponseEntity<String> response =
                http.exchange(
                        url("/api/console/today"),
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(CONSOLE_ORIGIN);
    }

    /** An explicit list, never a wildcard — this API answers with a shop's takings. */
    @Test
    void anUnknownOriginIsNotGrantedAccess() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://not-the-console.example.com");
        headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");

        ResponseEntity<String> response =
                http.exchange(
                        url("/api/console/today"),
                        HttpMethod.OPTIONS,
                        new HttpEntity<>(headers),
                        String.class);

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void theWildcardIsNeverReturned() {
        ResponseEntity<String> response = preflight("/api/console/today", "GET");

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNotEqualTo("*");
    }

    private ResponseEntity<String> preflight(String path, String method) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(CONSOLE_ORIGIN);
        headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
        headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type");
        return http.exchange(url(path), HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}

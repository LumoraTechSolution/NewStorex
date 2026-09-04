package com.lumora.pos.setup;

import java.time.Duration;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Asks the cloud whether a token is real, before the wizard saves it (M5-03).
 *
 * <h2>Why this exists</h2>
 *
 * The first version of the wizard took a token, wrote it to {@code runtime.json} and said "Saved."
 * A shop was then set up with a token the cloud rejects, and nothing said so: the till sold
 * perfectly, printed perfectly, and queued 62 outbox rows that were never going anywhere. The
 * problem was found by reading the database, which is not a thing a shopkeeper does.
 *
 * <p>That is the exact failure the wizard was written to remove — it was the criticism of the
 * {@code setx /M} procedure it replaced — and it survived into the replacement because the wizard
 * only checked that the field was non-empty. Non-empty is not a credential.
 *
 * <h2>What it actually asks</h2>
 *
 * The entitlement feed, which is the one cloud endpoint a till may call with a lapsed licence (see
 * {@code TenantAuthFilter}'s allowlist). That makes it the right probe: a shop whose licence ran
 * out yesterday still has a valid token, and refusing to save it during setup would strand exactly
 * the shop that most needs to be told why.
 *
 * <p>The distinction that matters is between <b>"the cloud says no"</b> and <b>"the cloud did not
 * answer"</b>. The first is a wrong token and must block. The second is an unreachable network,
 * which on this product is a normal state and must not — a shop being set up in a back room with
 * no signal is still being set up correctly. §A's whole point is that the network is on the
 * critical path of nothing.
 */
@Service
@Profile("desktop")
public class CloudCredentialCheck {

    /**
     * Short. A shopkeeper is watching a button, and the answer to "is this token good" is one round
     * trip to an endpoint that does a single indexed lookup. A cloud that has scaled to zero takes
     * longer than this and will be reported as unreachable, which is the honest answer at that
     * moment and is not fatal — {@link Result#reachable} is what the wizard branches on.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    /**
     * @param ok the token authenticated
     * @param reachable whether the cloud answered at all. False means we learned nothing about the
     *     token, and the wizard must let setup continue rather than blocking on a network the till
     *     is designed not to need.
     * @param shopName what the cloud calls this tenant, so the wizard can show it back. Seeing
     *     "jeewa stores" confirms the token in a way no green tick does; seeing a different shop's
     *     name is the mistake caught before a day of sales lands in the wrong ledger.
     * @param detail a sentence for the screen when something is wrong
     */
    public record Result(boolean ok, boolean reachable, String shopName, String detail) {}

    public Result check(String cloudUrl, String token) {
        String url = cloudUrl == null ? "" : cloudUrl.trim();
        String bearer = token == null ? "" : token.trim();

        if (bearer.isEmpty()) {
            return new Result(false, true, null, "No token given.");
        }
        if (url.isEmpty()) {
            // Checked here rather than left to fail as a connection error, because the failure it
            // produces is genuinely confusing: with no URL the till falls back to
            // http://127.0.0.1:8082 — itself — and reports "connection refused" about a cloud the
            // operator can see is running.
            return new Result(false, true, null, "A cloud address is needed to check the token.");
        }
        if (!url.toLowerCase(Locale.ROOT).startsWith("http://")
                && !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return new Result(false, true, null, "The cloud address must start with https://.");
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) TIMEOUT.toMillis());
        factory.setReadTimeout((int) TIMEOUT.toMillis());

        try {
            Entitlement answer =
                    RestClient.builder()
                            .baseUrl(url)
                            .requestFactory(factory)
                            .build()
                            .get()
                            .uri("/api/sync/entitlement")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                            .retrieve()
                            .body(Entitlement.class);

            // A 200 with an empty body is not a cloud we understand. Treated as unreachable rather
            // than as a bad token: something is answering on that address and it is not this API,
            // which is a wrong-URL problem and not a wrong-token one.
            if (answer == null) {
                return new Result(false, false, null, "That address answered, but not like StoreX Cloud.");
            }
            return new Result(true, true, answer.tenantName(), null);

        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
            // The one unambiguous answer. The cloud looked the token up and does not know it —
            // unknown, revoked, or belonging to a tenant that has been suspended or deleted.
            return new Result(
                    false,
                    true,
                    null,
                    "The cloud does not recognise that token. It may have been revoked, or belong to"
                            + " a shop that no longer exists — issue a new one and try again.");
        } catch (org.springframework.web.client.HttpClientErrorException
                | org.springframework.web.client.HttpServerErrorException e) {
            return new Result(false, false, null, "The cloud answered with an error: " + e.getMessage());
        } catch (Exception e) {
            // Unreachable: no signal, a sleeping host, a typo in the address. Not a verdict on the
            // token, and deliberately not fatal to setup.
            return new Result(false, false, null, "Could not reach that address.");
        }
    }

    /**
     * Only the field this check needs.
     *
     * <p>Deliberately not {@code com.lumora.pos.sync.Entitlement}: that record is the contract
     * between the two profiles and gains fields over time, and binding the whole of it here would
     * make an older cloud's response fail to deserialise during setup — turning "your cloud is a
     * version behind" into "your token is bad", which is the opposite of what this class is for.
     */
    private record Entitlement(String tenantName) {}
}

package com.lumora.pos.sync;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled turn the drain off entirely. Tests drive the worker by hand; a developer
 *     debugging a till may want it quiet.
 * @param batchSize how many outbox rows go up at once
 * @param backoffBase first retry delay, doubled per attempt
 * @param backoffMax the cap. Unbounded backoff means a till that recovers at 3am does not sync
 *     until someone notices.
 * @param entitlementInterval how often the downward pull actually reaches the cloud (M4-09).
 *     Separate from {@code interval} and far longer, because the two carry different urgency: an
 *     unsent sale should leave within seconds, whereas a licence that runs out at noon can be
 *     noticed at five past. Asking every ten seconds would be roughly nine thousand requests a day
 *     to learn a date that changes once a year.
 * @param token the tenant API token this till presents to the cloud (M4-01). Provisioned at
 *     activation by M5-03's first-run wizard; until then it is set from the environment. Null is a
 *     legitimate state — a till that has not been activated yet simply queues — so it is checked
 *     rather than defaulted, because the default for a credential is always the wrong one.
 */
@ConfigurationProperties(prefix = "lumora.sync")
public record SyncProperties(
        boolean enabled,
        String cloudUrl,
        String token,
        int batchSize,
        Duration requestTimeout,
        Duration backoffBase,
        Duration backoffMax,
        Duration entitlementInterval) {

    public SyncProperties {
        if (batchSize <= 0) batchSize = 100;
        if (requestTimeout == null) requestTimeout = Duration.ofSeconds(15);
        if (backoffBase == null) backoffBase = Duration.ofSeconds(5);
        if (backoffMax == null) backoffMax = Duration.ofMinutes(5);
        if (cloudUrl == null || cloudUrl.isBlank()) cloudUrl = "http://127.0.0.1:8082";
        if (entitlementInterval == null) entitlementInterval = Duration.ofMinutes(5);
    }

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    /** Capped exponential: base × 2^(attempts−1), never beyond the cap. */
    public Duration backoffFor(int attempts) {
        if (attempts <= 0) {
            return backoffBase;
        }
        long millis = backoffBase.toMillis();
        for (int i = 1; i < attempts && millis < backoffMax.toMillis(); i++) {
            millis *= 2;
        }
        return Duration.ofMillis(Math.min(millis, backoffMax.toMillis()));
    }
}

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
 */
@ConfigurationProperties(prefix = "lumora.sync")
public record SyncProperties(
        boolean enabled,
        String cloudUrl,
        int batchSize,
        Duration requestTimeout,
        Duration backoffBase,
        Duration backoffMax) {

    public SyncProperties {
        if (batchSize <= 0) batchSize = 100;
        if (requestTimeout == null) requestTimeout = Duration.ofSeconds(15);
        if (backoffBase == null) backoffBase = Duration.ofSeconds(5);
        if (backoffMax == null) backoffMax = Duration.ofMinutes(5);
        if (cloudUrl == null || cloudUrl.isBlank()) cloudUrl = "http://127.0.0.1:8082";
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

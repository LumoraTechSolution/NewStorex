package com.lumora.pos.cloud;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * The cloud's filter chain: CORS, then authentication (M4-01, M4-05).
 *
 * <p>Only under the {@code cloud} profile. The shop PC runs the same jar and must not acquire a
 * second authentication scheme on its loopback API, where M3-09 already settled who may do what.
 */
@Configuration
@Profile("cloud")
public class CloudSecurityConfig {

    /**
     * CORS, and why it is needed at all.
     *
     * <p>The console is a static PWA on its own origin — {@code localhost:3001} in development, a
     * Vercel domain in production — and the API is this service on another. Every request between
     * them is cross-origin, so without this a browser refuses them before they leave the machine and
     * reports the uninformative "Failed to fetch". The till never hit this because it is a different
     * profile on a different port and is not this app.
     *
     * <p><b>An explicit origin list, never a wildcard.</b> This API answers with a shop's takings,
     * so the set of pages allowed to read it is a decision worth writing down. The list is
     * configuration because the production origin is not known at build time.
     *
     * <p>{@code allowCredentials} stays false. The console authenticates with an {@code
     * Authorization} header rather than a cookie, so nothing here needs the browser to attach
     * ambient credentials — and leaving it off means a misconfigured origin cannot be combined with
     * a session cookie somebody adds later without noticing.
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(
            @Value("${lumora.console.allowed-origins:http://localhost:3001,http://127.0.0.1:3001}")
                    List<String> allowedOrigins) {

        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        // POST covers every write the platform API makes; there is deliberately no DELETE or PUT
        // anywhere in this service, because nothing it owns is deleted or replaced wholesale —
        // credentials are revoked, licences are appended, owners are deactivated (M4-08).
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        // An hour. Preflights are pure overhead on every distinct request shape, and nothing in
        // this policy changes without a redeploy anyway.
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);

        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.addUrlPatterns("/api/*");
        // Ahead of the auth filter, and that ordering is load-bearing. A preflight carries no
        // Authorization header — it is the browser asking whether it may send one — so if
        // authentication ran first every preflight would be answered 401 and no request would ever
        // follow it. CorsFilter answers preflights itself and never calls the rest of the chain.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        return registration;
    }

    /**
     * Mapped to {@code /api/*} rather than to the endpoints that exist today, so an endpoint added
     * later is authenticated by default and has to be deliberately exempted to not be.
     * {@code /actuator/**} is outside the pattern: a load balancer has to be able to health-check
     * the process without holding a shop's key (M4-10).
     */
    @Bean
    public FilterRegistrationBean<TenantAuthFilter> tenantAuthFilter(
            TenantCredentialService tillCredentials,
            ConsoleSessionService consoleSessions,
            PlatformSessionService platformSessions) {
        FilterRegistrationBean<TenantAuthFilter> registration =
                new FilterRegistrationBean<>(
                        new TenantAuthFilter(tillCredentials, consoleSessions, platformSessions));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return registration;
    }
}

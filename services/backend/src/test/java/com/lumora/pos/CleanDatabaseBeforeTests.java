package com.lumora.pos;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Gives every integration test a pristine schema: drop everything, then run the migrations from
 * scratch. That makes the migration path itself part of what the suite covers — a migration that
 * only works on an already-populated database fails here rather than on a till.
 *
 * <p>Applies to the {@code test} profile only, and refuses to touch anything that is not the
 * disposable test database. A stray {@code TEST_DATABASE_URL} should cost a failed test run, never
 * a developer's local shop data.
 */
@Configuration
@Profile("test")
public class CleanDatabaseBeforeTests {

    private static final String REQUIRED_DATABASE = "lumora_test";

    @Bean
    FlywayMigrationStrategy cleanThenMigrate(DataSource dataSource) {
        assertPointedAtTheTestDatabase(dataSource);
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }

    private void assertPointedAtTheTestDatabase(DataSource dataSource) {
        String url;
        try (var connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL();
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the JDBC URL to verify it is safe to clean", e);
        }
        if (url == null || !url.contains(REQUIRED_DATABASE)) {
            throw new IllegalStateException(
                    "Refusing to clean '"
                            + url
                            + "'. Integration tests only ever drop the database named '"
                            + REQUIRED_DATABASE
                            + "'. Start it with: docker compose up -d db-test");
        }
    }
}

package com.lumora.pos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the desktop profile against a real Postgres 16 and asserts Flyway actually ran.
 *
 * <p>The web layer stays off: the desktop profile binds a fixed 127.0.0.1:8081, which would collide
 * with a backend the developer already has running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class ApplicationBootTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void contextLoadsAndFlywayAppliedTheBaseline() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isNotNull().isPositive();
    }

    @Test
    void baselineInstalledTheUpdatedAtConvention() {
        // V1's whole purpose: the trigger function later migrations attach to.
        Boolean exists =
                jdbc.queryForObject(
                        "SELECT exists(SELECT 1 FROM pg_proc WHERE proname = 'set_updated_at')",
                        Boolean.class);
        assertThat(exists).isTrue();
    }

    @Test
    void runsOnPostgresNotAnInMemoryStandIn() {
        String version = jdbc.queryForObject("SELECT version()", String.class);
        assertThat(version).startsWith("PostgreSQL 16");
    }
}

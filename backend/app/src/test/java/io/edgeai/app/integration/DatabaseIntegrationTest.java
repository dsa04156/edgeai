package io.edgeai.app.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DatabaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.flywaydb.core.Flyway flyway;

    @Test
    void flywayMigratesARealPostgresDatabase() {
        assertThat(flyway.getConfiguration().getDefaultSchema()).isEqualTo("edgeai");
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("PostgreSQL");
        assertThat(jdbc.queryForObject(
            "select count(*) from information_schema.schemata where schema_name = 'edgeai'",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select success from edgeai.flyway_schema_history where version = '1'",
            Boolean.class)).isTrue();
    }
}

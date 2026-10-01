package io.edgeai.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DatabaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayMigratesARealPostgresDatabase() {
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("PostgreSQL");
        assertThat(jdbc.queryForObject(
            "select count(*) from information_schema.schemata where schema_name = 'edgeai'",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select success from flyway_schema_history where version = '1'",
            Boolean.class)).isTrue();
    }
}

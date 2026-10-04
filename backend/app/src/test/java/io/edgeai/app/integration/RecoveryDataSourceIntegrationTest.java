package io.edgeai.app.integration;

import com.zaxxer.hikari.HikariDataSource;
import io.edgeai.app.config.RecoveryConfiguration;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

/** Own databases only. Exercises PostgreSQL enforcement, not a mock connection's readOnly flag. */
class RecoveryDataSourceIntegrationTest {
    private Connection admin;
    private String database, oid, prefix, user, password;

    @BeforeEach void create() throws Exception {
        user = required("EDGEAI_DB_USER"); password = required("EDGEAI_DB_PASSWORD");
        prefix = "jdbc:postgresql://" + System.getenv().getOrDefault("EDGEAI_DB_HOST", "127.0.0.1") + ":" + required("EDGEAI_DB_PORT") + "/";
        database = "edgeai_restore_guard_" + UUID.randomUUID().toString().replace("-", "");
        admin = DriverManager.getConnection(prefix + "postgres", user, password);
        try (var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try (var rows = statement.executeQuery("SELECT oid::text FROM pg_database WHERE datname='" + database + "'")) {
                assertThat(rows.next()).isTrue(); oid = rows.getString(1);
            }
            statement.execute("COMMENT ON DATABASE " + database + " IS 'edgeai-restore:" + UUID.randomUUID().toString().replace("-", "") + "'");
        }
        try (var connection = DriverManager.getConnection(prefix + database, user, password); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE restore_write_probe(value integer)");
        }
    }

    @AfterEach void remove() throws Exception {
        if (admin == null) return;
        var connection = admin;
        try (connection; var statement = connection.createStatement()) {
            if (oid != null) {
                try (var rows = statement.executeQuery("SELECT oid::text FROM pg_database WHERE datname='" + database + "'")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo(oid);
                }
                statement.execute("DROP DATABASE " + database);
            }
        }
    }

    private HikariDataSource pool() {
        var pool = new HikariDataSource(); pool.setJdbcUrl(prefix + database); pool.setUsername(user); pool.setPassword(password);
        pool.setMaximumPoolSize(2); pool.setMinimumIdle(0); pool.setConnectionTimeout(5000);
        return pool;
    }

    @Test void restoredDatabaseIsRejectedEvenWhenFlywayIsDisabledAndFailedPoolIsClosed() {
        var guard = RecoveryConfiguration.recoveryDataSourceGuard(new MockEnvironment().withProperty("spring.flyway.enabled", "false"));
        try (var pool = pool()) {
            guard.postProcessBeforeInitialization(pool, "dataSource");
            assertThatThrownBy(() -> guard.postProcessAfterInitialization(pool, "dataSource"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("quarantined");
            assertThat(pool.isClosed()).isTrue();
        }
    }

    @Test void inspectionPoolRejectsActualAutocommitAndTransactionalWritesOnEveryNewConnection() throws Exception {
        var guard = RecoveryConfiguration.recoveryDataSourceGuard(new MockEnvironment().withProperty("edgeai.recovery.inspect-only", "true"));
        try (var pool = pool()) {
            guard.postProcessBeforeInitialization(pool, "dataSource"); guard.postProcessAfterInitialization(pool, "dataSource");
            try (var first = pool.getConnection(); var second = pool.getConnection()) {
                for (var connection : java.util.List.of(first, second)) {
                    try (var statement = connection.createStatement()) {
                        try (var rows = statement.executeQuery("SELECT count(*) FROM restore_write_probe")) {
                            assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
                        }
                        for (boolean autocommit : new boolean[] {true, false}) {
                            connection.setAutoCommit(autocommit);
                            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO restore_write_probe VALUES(1)"))
                                .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("25006"));
                            if (!autocommit) connection.rollback();
                        }
                    }
                }
            }
        }
    }

    @Test void inspectionRejectsExecutionFlagsAndUnmarkedDatabase() throws Exception {
        for (String feature : java.util.List.of("edgeai.runtime.enabled", "edgeai.vd.enabled", "edgeai.remote.enabled",
                "edgeai.stream.enabled", "edgeai.stream.bindings-enabled", "edgeai.stream.runs-enabled", "edgeai.kubernetes.enabled")) {
            var guard = RecoveryConfiguration.recoveryDataSourceGuard(new MockEnvironment()
                .withProperty("edgeai.recovery.inspect-only", "true").withProperty(feature, "true"));
            try (var pool = pool()) {
                assertThatThrownBy(() -> guard.postProcessBeforeInitialization(pool, "dataSource"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("features disabled");
            }
        }
        try (var statement = admin.createStatement()) { statement.execute("COMMENT ON DATABASE " + database + " IS NULL"); }
        var guard = RecoveryConfiguration.recoveryDataSourceGuard(new MockEnvironment().withProperty("edgeai.recovery.inspect-only", "true"));
        try (var pool = pool()) {
            guard.postProcessBeforeInitialization(pool, "dataSource");
            assertThatThrownBy(() -> guard.postProcessAfterInitialization(pool, "dataSource"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("marked restored database");
            assertThat(pool.isClosed()).isTrue();
        }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Real PostgreSQL test requires " + key);
        return value;
    }
}

package io.edgeai.app.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** A restored snapshot cannot regain execution authority merely by starting the API. */
@Configuration
public class RecoveryConfiguration {
    private static final List<String> EXTERNAL_FEATURES = List.of("edgeai.runtime.enabled", "edgeai.vd.enabled",
        "edgeai.remote.enabled", "edgeai.stream.enabled", "edgeai.stream.bindings-enabled",
        "edgeai.stream.runs-enabled", "edgeai.kubernetes.enabled");

    @Bean
    public static BeanPostProcessor recoveryDataSourceGuard(Environment environment) {
        boolean inspection = environment.getProperty("edgeai.recovery.inspect-only", Boolean.class, false);
        return new BeanPostProcessor() {
            @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                if (inspection && bean instanceof DataSource) {
                    for (String feature : EXTERNAL_FEATURES)
                        if (environment.getProperty(feature, Boolean.class, false))
                            throw new IllegalStateException("Recovery inspection requires external execution and inventory features disabled");
                    if (!environment.getProperty("spring.flyway.enabled", Boolean.class, true))
                        throw new IllegalStateException("Recovery inspection requires Flyway validation enabled");
                    if (bean instanceof HikariDataSource pool) {
                        if (pool.getConnectionInitSql() != null && !pool.getConnectionInitSql().isBlank())
                            throw new IllegalStateException("Recovery inspection cannot replace a custom connection initialization command");
                        pool.setConnectionInitSql("SET default_transaction_read_only = on");
                        pool.setReadOnly(true);
                    }
                }
                return bean;
            }

            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof DataSource dataSource)) return bean;
                try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT current_database(), shobj_description(oid,'pg_database'), " +
                         "current_setting('default_transaction_read_only') FROM pg_database WHERE datname=current_database()")) {
                    if (!rows.next()) throw new IllegalStateException("Database recovery identity is unavailable");
                    String database = rows.getString(1), marker = rows.getString(2);
                    boolean restored = database.startsWith("edgeai_restore_") || (marker != null && marker.startsWith("edgeai-restore:"));
                    if (restored && !inspection)
                        throw new IllegalStateException("Restored database is quarantined; only explicit recovery inspection is supported");
                    if (inspection && (marker == null || !marker.matches("edgeai-restore:[a-f0-9]{32}") ||
                            !"on".equals(rows.getString(3)) || !connection.isReadOnly()))
                        throw new IllegalStateException("Recovery inspection requires a marked restored database and read-only connections");
                } catch (SQLException failure) {
                    if (bean instanceof HikariDataSource pool) pool.close();
                    throw new IllegalStateException("Database recovery safety check failed (" + failure.getClass().getSimpleName() + ")");
                } catch (RuntimeException failure) {
                    if (bean instanceof HikariDataSource pool) pool.close();
                    throw failure;
                }
                return bean;
            }
        };
    }

    @Bean
    FlywayMigrationStrategy recoveryMigrationStrategy(Environment environment) {
        return flyway -> {
            if (environment.getProperty("edgeai.recovery.inspect-only", Boolean.class, false)) flyway.validate();
            else flyway.migrate();
        };
    }
}

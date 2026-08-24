package com.demeter.backend.migration;

import javax.sql.DataSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Readiness check for the schema contract. A live database connection alone is not enough: the
 * runtime must not serve traffic against an incomplete or partially failed migration set.
 */
@Component("databaseSchema")
public class DatabaseSchemaHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseSchemaProperties properties;

    public DatabaseSchemaHealthIndicator(DataSource dataSource, DatabaseSchemaProperties properties) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            int failedMigrations = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0",
                    Integer.class);
            String latestVersion = jdbcTemplate.queryForObject(
                    """
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success = 1 AND version IS NOT NULL
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """,
                    String.class);
            int actualVersion = parseVersion(latestVersion);
            if (failedMigrations != 0 || actualVersion < properties.minimumSchemaVersion()) {
                return Health.down()
                        .withDetail("minimumVersion", properties.minimumSchemaVersion())
                        .withDetail("actualVersion", actualVersion)
                        .withDetail("failedMigrations", failedMigrations)
                        .build();
            }
            return Health.up()
                    .withDetail("version", actualVersion)
                    .withDetail("minimumVersion", properties.minimumSchemaVersion())
                    .build();
        } catch (DataAccessException | NumberFormatException exception) {
            return Health.down(exception).build();
        }
    }

    private static int parseVersion(String value) {
        if (value == null || value.isBlank()) {
            throw new NumberFormatException("No successful Flyway migration exists");
        }
        return Integer.parseInt(value.trim());
    }
}

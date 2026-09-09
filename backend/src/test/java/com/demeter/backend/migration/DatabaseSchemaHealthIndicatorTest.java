package com.demeter.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseSchemaHealthIndicatorTest {

    @Test
    void reportsUpWhenTheLatestSuccessfulMigrationMeetsTheMinimumVersion() {
        JdbcDataSource dataSource = dataSource("schema-health-up");
        createFlywayHistory(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        insertMigration(jdbcTemplate, 1, "17", true);
        insertMigration(jdbcTemplate, 2, "22", true);

        var health = new DatabaseSchemaHealthIndicator(dataSource, new DatabaseSchemaProperties(22)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("version", 23);
        assertThat(health.getDetails()).containsEntry("minimumVersion", 22);
    }

    @Test
    void reportsDownWhenSchemaVersionIsTooOld() {
        JdbcDataSource dataSource = dataSource("schema-health-old");
        createFlywayHistory(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        insertMigration(jdbcTemplate, 1, "17", true);

        var health = new DatabaseSchemaHealthIndicator(dataSource, new DatabaseSchemaProperties(22)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("actualVersion", 17);
        assertThat(health.getDetails()).containsEntry("minimumVersion", 22);
        assertThat(health.getDetails()).containsEntry("failedMigrations", 0);
    }

    @Test
    void reportsDownWhenAnyMigrationFailed() {
        JdbcDataSource dataSource = dataSource("schema-health-failed");
        createFlywayHistory(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        insertMigration(jdbcTemplate, 1, "22", true);
        insertMigration(jdbcTemplate, 2, "19", false);

        var health = new DatabaseSchemaHealthIndicator(dataSource, new DatabaseSchemaProperties(22)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("failedMigrations", 1);
    }

    @Test
    void reportsDownWhenNoSuccessfulVersionExists() {
        JdbcDataSource dataSource = dataSource("schema-health-empty");
        createFlywayHistory(dataSource);

        var health = new DatabaseSchemaHealthIndicator(dataSource, new DatabaseSchemaProperties(22)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    private static void createFlywayHistory(JdbcDataSource dataSource) {
        new JdbcTemplate(dataSource).execute("""
                CREATE TABLE flyway_schema_history (
                    installed_rank INT NOT NULL PRIMARY KEY,
                    version VARCHAR(50),
                    success BOOLEAN NOT NULL
                )
                """);
    }

    private static void insertMigration(
            JdbcTemplate jdbcTemplate,
            int installedRank,
            String version,
            boolean success) {
        jdbcTemplate.update(
                "INSERT INTO flyway_schema_history (installed_rank, version, success) VALUES (?, ?, ?)",
                installedRank,
                version,
                success);
    }

    private static JdbcDataSource dataSource(String databaseName) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }
}

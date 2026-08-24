package com.demeter.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseSchemaValidatorTest {

    @Test
    void validatesAnUpToDateSchemaAndRejectsAMissingMigration() {
        JdbcDataSource dataSource = dataSource("schema-validation-success");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(DatabaseSchemaValidator.validate(dataSource).validationSuccessful).isTrue();

        new JdbcTemplate(dataSource).update("DELETE FROM flyway_schema_history WHERE version = '15'");

        assertThatThrownBy(() -> DatabaseSchemaValidator.validate(dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("15")
                .hasMessageContaining("not applied");
    }

    private static JdbcDataSource dataSource(String databaseName) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }
}

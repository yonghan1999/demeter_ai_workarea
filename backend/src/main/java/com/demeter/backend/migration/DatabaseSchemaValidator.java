package com.demeter.backend.migration;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseSchemaValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaValidator.class);

    private final DataSource dataSource;

    public DatabaseSchemaValidator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        ValidateResult result = validate(dataSource);
        log.info(
                "Database schema validation completed: validationSuccessful={}, warnings={}",
                result.validationSuccessful,
                result.warnings.size());
    }

    static ValidateResult validate(DataSource dataSource) {
        Flyway flyway = Flyway.configure(DatabaseSchemaValidator.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load();
        ValidateResult result = flyway.validateWithResult();
        if (!result.validationSuccessful) {
            String details = result.invalidMigrations.stream()
                    .map(error -> error.version + ": " + error.errorDetails.errorMessage)
                    .collect(java.util.stream.Collectors.joining("; "));
            throw new IllegalStateException("Database schema validation failed: " + details);
        }
        return result;
    }
}

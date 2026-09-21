package com.demeter.backend.migration;

import java.util.Locale;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

public final class DatabaseMigrationMain {

    private static final Set<String> RESERVED_DATABASE_USERS = Set.of(
            "root", "mysql", "admin", "administrator",
            "demeter_api", "demeter_worker", "demeter_maintenance");

    private DatabaseMigrationMain() {
    }

    public static void main(String[] args) {
        MigrationSettings settings = MigrationSettings.fromEnvironment(System.getenv());
        MigrateResult result = migrate(settings);
        System.out.printf(
                "Database migration completed: version=%s, migrationsExecuted=%d%n",
                result.targetSchemaVersion,
                result.migrationsExecuted);
    }

    static MigrateResult migrate(MigrationSettings settings) {
        settings.validate();
        return Flyway.configure(DatabaseMigrationMain.class.getClassLoader())
                .dataSource(settings.url(), settings.username(), settings.password())
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .baselineOnMigrate(false)
                .load()
                .migrate();
    }

    record MigrationSettings(String url, String username, String password, boolean allowInsecureLocalConnection) {

        static MigrationSettings fromEnvironment(java.util.Map<String, String> environment) {
            return new MigrationSettings(
                    environment.get("MIGRATION_DB_URL"),
                    environment.get("MIGRATION_DB_USERNAME"),
                    environment.get("MIGRATION_DB_PASSWORD"),
                    Boolean.parseBoolean(environment.getOrDefault("MIGRATION_ALLOW_INSECURE_LOCAL", "false")));
        }

        void validate() {
            String databaseUrl = requireText(url, "MIGRATION_DB_URL is required");
            String databaseUsername = requireText(username, "MIGRATION_DB_USERNAME is required");
            if (RESERVED_DATABASE_USERS.contains(databaseUsername.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(
                        "MIGRATION_DB_USERNAME must use a dedicated migration account, not a privileged or runtime account");
            }
            String databasePassword = requireText(password, "MIGRATION_DB_PASSWORD is required");
            if (isPlaceholder(databasePassword)) {
                throw new IllegalStateException("MIGRATION_DB_PASSWORD must not use a development value");
            }

            String normalized = databaseUrl.toLowerCase(Locale.ROOT).replace(" ", "");
            if (!normalized.startsWith("jdbc:mysql:")) {
                throw new IllegalStateException("MIGRATION_DB_URL must use MySQL");
            }
            if (!allowInsecureLocalConnection
                    && !containsParameter(normalized, "sslmode", "verify_identity")) {
                throw new IllegalStateException("MIGRATION_DB_URL must set sslMode=VERIFY_IDENTITY");
            }
            if (!containsParameter(normalized, "connectiontimezone", "utc")
                    && !containsParameter(normalized, "servertimezone", "utc")) {
                throw new IllegalStateException("MIGRATION_DB_URL must use the UTC connection time zone");
            }
            boolean insecureTlsOption = containsParameter(normalized, "allowpublickeyretrieval", "true")
                    || containsParameter(normalized, "usessl", "false")
                    || containsParameter(normalized, "sslmode", "disabled");
            if (insecureTlsOption && !allowInsecureLocalConnection) {
                throw new IllegalStateException("MIGRATION_DB_URL contains an unsafe TLS option");
            }
            if (allowInsecureLocalConnection
                    && !containsParameter(normalized, "sslmode", "disabled")) {
                throw new IllegalStateException(
                        "MIGRATION_ALLOW_INSECURE_LOCAL requires sslMode=DISABLED to make the exception explicit");
            }
        }

        private static boolean containsParameter(String normalizedUrl, String name, String value) {
            return normalizedUrl.matches(
                    ".*[?&]" + java.util.regex.Pattern.quote(name)
                            + "=" + java.util.regex.Pattern.quote(value) + "(?:&.*)?$");
        }

        private static boolean isPlaceholder(String value) {
            String normalized = value.toLowerCase(Locale.ROOT);
            return "demeter".equals(normalized)
                    || normalized.contains("change-me")
                    || normalized.contains("replace-with");
        }

        private static String requireText(String value, String message) {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(message);
            }
            return value.trim();
        }
    }
}

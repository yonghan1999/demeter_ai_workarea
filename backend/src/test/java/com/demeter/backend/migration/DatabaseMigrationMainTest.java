package com.demeter.backend.migration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DatabaseMigrationMainTest {

    @ParameterizedTest
    @ValueSource(strings = {"demeter_migrator", "production_schema_migrator"})
    void acceptsAProductionMySqlConnectionWithIdentityVerification(String username) {
        var settings = new DatabaseMigrationMain.MigrationSettings(
                "jdbc:mysql://mysql:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                username,
                "a-strong-migration-password",
                false);

        assertThatCode(settings::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingCredentialsAndUnsafeDatabaseConnections() {
        assertThatThrownBy(() -> new DatabaseMigrationMain.MigrationSettings(
                        "jdbc:mysql://mysql:3306/demeter?sslMode=REQUIRED&connectionTimeZone=UTC",
                        "demeter_migrator",
                        "a-strong-migration-password",
                        false)
                .validate())
                .hasMessageContaining("sslMode=VERIFY_IDENTITY");

        assertThatThrownBy(() -> new DatabaseMigrationMain.MigrationSettings(
                        "jdbc:mysql://mysql:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                        "demeter_migrator",
                        "change-me",
                        false)
                .validate())
                .hasMessageContaining("development value");

        assertThatThrownBy(() -> new DatabaseMigrationMain.MigrationSettings(null, null, null, false).validate())
                .hasMessageContaining("MIGRATION_DB_URL");
    }

    @Test
    void allowsAnExplicitInsecureConnectionOnlyForLocalMigration() {
        var localSettings = new DatabaseMigrationMain.MigrationSettings(
                "jdbc:mysql://mysql:3306/demeter?sslMode=DISABLED&connectionTimeZone=UTC",
                "demeter_migrator",
                "a-strong-local-migration-password",
                true);

        assertThatCode(localSettings::validate).doesNotThrowAnyException();

        assertThatThrownBy(() -> new DatabaseMigrationMain.MigrationSettings(
                        "jdbc:mysql://mysql:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                        "demeter_migrator",
                        "a-strong-local-migration-password",
                        true)
                .validate())
                .hasMessageContaining("sslMode=DISABLED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"root", "mysql", "admin", "administrator", "demeter_api",
            "demeter_worker", "demeter_maintenance", "ROOT", " Demeter_API "})
    void rejectsPrivilegedAndRuntimeAccounts(String username) {
        assertThatThrownBy(() -> new DatabaseMigrationMain.MigrationSettings(
                        "jdbc:mysql://mysql:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                        username,
                        "a-strong-migration-password",
                        false)
                .validate())
                .hasMessageContaining("dedicated migration account");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingMigrationUsername(String username) {
        assertThatThrownBy(() -> new DatabaseMigrationMain.MigrationSettings(
                        "jdbc:mysql://mysql:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                        username,
                        "a-strong-migration-password",
                        false)
                .validate())
                .hasMessageContaining("MIGRATION_DB_USERNAME is required");
    }
}

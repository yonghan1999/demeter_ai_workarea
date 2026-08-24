package com.demeter.backend.migration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseMigrationMainTest {

    @Test
    void acceptsAProductionMySqlConnectionWithIdentityVerification() {
        var settings = new DatabaseMigrationMain.MigrationSettings(
                "jdbc:mysql://mysql:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                "demeter_migrator",
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

    @Test
    void requiresTheDedicatedMigrationAccount() {
        assertThatThrownBy(() -> new DatabaseMigrationMain.MigrationSettings(
                        "jdbc:mysql://mysql:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                        "demeter_api",
                        "a-strong-migration-password",
                        false)
                .validate())
                .hasMessageContaining("dedicated demeter_migrator");
    }
}

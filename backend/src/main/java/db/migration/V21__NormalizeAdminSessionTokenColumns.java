package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V20 originally used fixed-width columns. Session hashes and identifiers must
 * be variable-width so H2 and MySQL do not pad values differently.
 */
public class V21__NormalizeAdminSessionTokenColumns extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        String product = context.getConnection().getMetaData().getDatabaseProductName().toLowerCase();
        try (Statement sql = context.getConnection().createStatement()) {
            if (product.contains("mysql")) {
                sql.execute("ALTER TABLE admin_sessions MODIFY COLUMN id VARCHAR(36) NOT NULL, MODIFY COLUMN token_hash VARCHAR(64) NOT NULL");
            } else {
                sql.execute("ALTER TABLE admin_sessions ALTER COLUMN id VARCHAR(36)");
                sql.execute("ALTER TABLE admin_sessions ALTER COLUMN token_hash VARCHAR(64)");
            }
        }
    }
}

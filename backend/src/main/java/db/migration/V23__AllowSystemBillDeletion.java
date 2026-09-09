package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Allows audited system deletions without inventing a business-user identity. */
public class V23__AllowSystemBillDeletion extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute(dropCheckSql(context.getConnection()));
            statement.execute("""
                    ALTER TABLE bills
                        ADD CONSTRAINT ck_bills_deletion_state CHECK (
                            (deleted_at IS NULL
                                AND deleted_by IS NULL
                                AND delete_reason IS NULL)
                            OR (deleted_at IS NOT NULL
                                AND delete_reason IS NOT NULL)
                        )
                    """);
        }
    }

    private static String dropCheckSql(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        return product.contains("mysql")
                ? "ALTER TABLE bills DROP CHECK ck_bills_deletion_state"
                : "ALTER TABLE bills DROP CONSTRAINT ck_bills_deletion_state";
    }
}

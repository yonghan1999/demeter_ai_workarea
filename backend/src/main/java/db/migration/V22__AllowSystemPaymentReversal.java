package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Allows audited system reversals without inventing a business-user identity. */
public class V22__AllowSystemPaymentReversal extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute(dropCheckSql(context.getConnection()));
            statement.execute("""
                    ALTER TABLE payments
                        ADD CONSTRAINT ck_payments_reversal_state CHECK (
                            (status = 'ACTIVE'
                                AND reversed_at IS NULL
                                AND reversed_by IS NULL
                                AND reversal_reason IS NULL
                                AND reversal_idempotency_key IS NULL
                                AND reversal_request_hash IS NULL)
                            OR (status = 'REVERSED'
                                AND reversed_at IS NOT NULL
                                AND reversal_reason IS NOT NULL
                                AND reversal_idempotency_key IS NOT NULL
                                AND reversal_request_hash IS NOT NULL)
                        )
                    """);
        }
    }

    private static String dropCheckSql(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        return product.contains("mysql")
                ? "ALTER TABLE payments DROP CHECK ck_payments_reversal_state"
                : "ALTER TABLE payments DROP CONSTRAINT ck_payments_reversal_state";
    }
}

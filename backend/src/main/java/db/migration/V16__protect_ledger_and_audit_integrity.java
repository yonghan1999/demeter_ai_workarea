package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Database-side guardrails for the append-only ledger and sensitive source metadata. */
public class V16__protect_ledger_and_audit_integrity extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        if (isMySql(context.getConnection())) {
            executeMySqlTriggers(context.getConnection());
        }
    }

    private static boolean isMySql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT)
                .contains("mysql");
    }

    private static void executeMySqlTriggers(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            execute(statement, """
                    CREATE TRIGGER trg_demeter_payments_immutable_update
                    BEFORE UPDATE ON payments
                    FOR EACH ROW
                    BEGIN
                        IF NOT (OLD.tenant_id <=> NEW.tenant_id)
                           OR NOT (OLD.bill_id <=> NEW.bill_id)
                           OR NOT (OLD.amount <=> NEW.amount)
                           OR NOT (OLD.method <=> NEW.method)
                           OR NOT (OLD.paid_at <=> NEW.paid_at)
                           OR NOT (OLD.reference_no <=> NEW.reference_no)
                           OR NOT (OLD.note <=> NEW.note)
                           OR NOT (OLD.idempotency_key <=> NEW.idempotency_key)
                           OR NOT (OLD.request_hash <=> NEW.request_hash)
                           OR NOT (OLD.created_by <=> NEW.created_by)
                           OR NOT (OLD.created_at <=> NEW.created_at)
                        THEN
                            SIGNAL SQLSTATE '45000'
                                SET MESSAGE_TEXT = 'Payment ledger entries are append-only';
                        END IF;
                        IF OLD.status = 'REVERSED'
                           AND (NOT (OLD.status <=> NEW.status)
                                OR NOT (OLD.reversed_at <=> NEW.reversed_at)
                                OR NOT (OLD.reversed_by <=> NEW.reversed_by)
                                OR NOT (OLD.reversal_reason <=> NEW.reversal_reason)
                                OR NOT (OLD.reversal_idempotency_key <=> NEW.reversal_idempotency_key)
                                OR NOT (OLD.reversal_request_hash <=> NEW.reversal_request_hash))
                        THEN
                            SIGNAL SQLSTATE '45000'
                                SET MESSAGE_TEXT = 'A payment reversal cannot be changed';
                        END IF;
                    END
                    """);
            execute(statement, """
                    CREATE TRIGGER trg_demeter_payments_no_delete
                    BEFORE DELETE ON payments
                    FOR EACH ROW
                    SIGNAL SQLSTATE '45000'
                        SET MESSAGE_TEXT = 'Payment ledger entries cannot be deleted';
                    """);
            execute(statement, """
                    CREATE TRIGGER trg_demeter_bills_immutable_update
                    BEFORE UPDATE ON bills
                    FOR EACH ROW
                    BEGIN
                        IF NOT (OLD.tenant_id <=> NEW.tenant_id)
                           OR NOT (OLD.code <=> NEW.code)
                           OR NOT (OLD.creation_idempotency_key <=> NEW.creation_idempotency_key)
                           OR NOT (OLD.creation_request_hash <=> NEW.creation_request_hash)
                           OR NOT (OLD.created_by <=> NEW.created_by)
                           OR NOT (OLD.created_at <=> NEW.created_at)
                        THEN
                            SIGNAL SQLSTATE '45000'
                                SET MESSAGE_TEXT = 'Bill identity fields are immutable';
                        END IF;
                    END
                    """);
            execute(statement, """
                    CREATE TRIGGER trg_demeter_bills_ledger_consistency_insert
                    BEFORE INSERT ON bills
                    FOR EACH ROW
                    BEGIN
                        IF NEW.paid_amount <> 0 OR NEW.status <> 'UNPAID'
                        THEN
                            SIGNAL SQLSTATE '45000'
                                SET MESSAGE_TEXT = 'A new bill must start with an unpaid ledger';
                        END IF;
                    END
                    """);
            execute(statement, """
                    CREATE TRIGGER trg_demeter_bills_ledger_consistency_update
                    BEFORE UPDATE ON bills
                    FOR EACH ROW
                    BEGIN
                        IF NEW.paid_amount <> (
                            SELECT COALESCE(SUM(payment.amount), 0)
                            FROM payments payment
                            WHERE payment.tenant_id = NEW.tenant_id
                              AND payment.bill_id = NEW.id
                              AND payment.status = 'ACTIVE'
                        )
                        OR NEW.status <> CASE
                            WHEN NEW.paid_amount = 0 THEN 'UNPAID'
                            WHEN NEW.paid_amount < NEW.amount THEN 'PARTIALLY_PAID'
                            ELSE 'PAID'
                        END
                        THEN
                            SIGNAL SQLSTATE '45000'
                                SET MESSAGE_TEXT = 'Bill summary does not match the payment ledger';
                        END IF;
                    END
                    """);
            execute(statement, """
                    CREATE TRIGGER trg_demeter_audit_no_update
                    BEFORE UPDATE ON audit_events
                    FOR EACH ROW
                    SIGNAL SQLSTATE '45000'
                        SET MESSAGE_TEXT = 'Audit events cannot be modified';
                    """);
            execute(statement, """
                    CREATE TRIGGER trg_demeter_audit_no_delete
                    BEFORE DELETE ON audit_events
                    FOR EACH ROW
                    SIGNAL SQLSTATE '45000'
                        SET MESSAGE_TEXT = 'Audit events cannot be deleted';
                    """);
            execute(statement, """
                    CREATE TRIGGER trg_demeter_ocr_source_immutable_update
                    BEFORE UPDATE ON ocr_tasks
                    FOR EACH ROW
                    BEGIN
                        IF NOT (OLD.tenant_id <=> NEW.tenant_id)
                           OR NOT (OLD.created_by <=> NEW.created_by)
                           OR NOT (OLD.storage_key <=> NEW.storage_key)
                           OR NOT (OLD.original_filename <=> NEW.original_filename)
                           OR NOT (OLD.content_type <=> NEW.content_type)
                           OR NOT (OLD.size_bytes <=> NEW.size_bytes)
                           OR NOT (OLD.content_sha256 <=> NEW.content_sha256)
                           OR NOT (OLD.idempotency_key <=> NEW.idempotency_key)
                           OR NOT (OLD.request_hash <=> NEW.request_hash)
                           OR NOT (OLD.created_at <=> NEW.created_at)
                        THEN
                            SIGNAL SQLSTATE '45000'
                                SET MESSAGE_TEXT = 'OCR source metadata is immutable';
                        END IF;
                    END
                    """);
            execute(statement, """
                    CREATE TRIGGER trg_demeter_sessions_immutable_update
                    BEFORE UPDATE ON auth_sessions
                    FOR EACH ROW
                    BEGIN
                        IF NOT (OLD.id <=> NEW.id)
                           OR NOT (OLD.user_id <=> NEW.user_id)
                           OR NOT (OLD.token_hash <=> NEW.token_hash)
                           OR NOT (OLD.expires_at <=> NEW.expires_at)
                           OR NOT (OLD.created_at <=> NEW.created_at)
                        THEN
                            SIGNAL SQLSTATE '45000'
                                SET MESSAGE_TEXT = 'Authentication session identity is immutable';
                        END IF;
                    END
                    """);
        }
    }

    private static void execute(Statement statement, String sql) throws SQLException {
        statement.execute(sql);
    }
}

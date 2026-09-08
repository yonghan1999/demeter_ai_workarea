#!/bin/sh
set -eu

: "${DB_API_PASSWORD:?DB_API_PASSWORD is required}"
: "${DB_WORKER_PASSWORD:?DB_WORKER_PASSWORD is required}"
: "${DB_MAINTENANCE_PASSWORD:?DB_MAINTENANCE_PASSWORD is required}"
: "${DB_MIGRATOR_PASSWORD:?DB_MIGRATOR_PASSWORD is required}"

mysql_host="${MYSQL_HOST:-mysql}"
mysql_port="${MYSQL_PORT:-3306}"
ssl_mode="${MYSQL_SSL_MODE:-VERIFY_IDENTITY}"
db_name="${DB_NAME:-demeter}"

case "$db_name" in
  ''|*[!A-Za-z0-9_]*)
    echo "DB_NAME must contain only letters, numbers and underscores." >&2
    exit 64
    ;;
esac

password_for() {
  case "$1" in
    demeter_api) printf '%s' "$DB_API_PASSWORD" ;;
    demeter_worker) printf '%s' "$DB_WORKER_PASSWORD" ;;
    demeter_maintenance) printf '%s' "$DB_MAINTENANCE_PASSWORD" ;;
    demeter_migrator) printf '%s' "$DB_MIGRATOR_PASSWORD" ;;
    *) echo "Unknown database role: $1" >&2; exit 64 ;;
  esac
}

execute_as() {
  user="$1"
  sql="$2"
  password="$(password_for "$user")"
  if [ -n "${MYSQL_SSL_CA:-}" ]; then
    MYSQL_PWD="$password" mysql \
      --protocol=tcp \
      --host="$mysql_host" \
      --port="$mysql_port" \
      --user="$user" \
      --database="$db_name" \
      --ssl-mode="$ssl_mode" \
      --ssl-ca="$MYSQL_SSL_CA" \
      --batch --skip-column-names \
      --execute="$sql"
  else
    MYSQL_PWD="$password" mysql \
      --protocol=tcp \
      --host="$mysql_host" \
      --port="$mysql_port" \
      --user="$user" \
      --database="$db_name" \
      --ssl-mode="$ssl_mode" \
      --batch --skip-column-names \
      --execute="$sql"
  fi
}

allow() {
  user="$1"
  description="$2"
  sql="$3"
  if ! execute_as "$user" "$sql" >/dev/null; then
    echo "$user lacks required permission: $description" >&2
    exit 1
  fi
}

deny() {
  user="$1"
  description="$2"
  sql="$3"
  if execute_as "$user" "$sql" >/dev/null 2>&1; then
    echo "$user unexpectedly has forbidden permission: $description" >&2
    exit 1
  fi
}

allow demeter_api "API table reads and writes" "
  SELECT COUNT(*) FROM flyway_schema_history;
  SELECT COUNT(*) FROM tenants;
  SELECT COUNT(*) FROM users;
  SELECT COUNT(*) FROM auth_sessions;
  SELECT COUNT(*) FROM admin_sessions;
  SELECT COUNT(*) FROM admin_command_replays;
  SELECT COUNT(*) FROM bill_code_sequences;
  SELECT COUNT(*) FROM bills;
  SELECT COUNT(*) FROM bill_tags;
  SELECT COUNT(*) FROM payments;
  SELECT COUNT(*) FROM ocr_tasks;
  SELECT COUNT(*) FROM ocr_retry_commands;
  SELECT COUNT(*) FROM business_command_replays;
  SELECT COUNT(*) FROM audit_events;
  SELECT COUNT(*) FROM maintenance_runs;
  INSERT INTO tenants SELECT * FROM tenants WHERE 1 = 0;
  INSERT INTO users SELECT * FROM users WHERE 1 = 0;
  INSERT INTO auth_sessions SELECT * FROM auth_sessions WHERE 1 = 0;
  INSERT INTO admin_sessions SELECT * FROM admin_sessions WHERE 1 = 0;
  INSERT INTO admin_command_replays SELECT * FROM admin_command_replays WHERE 1 = 0;
  INSERT INTO bill_code_sequences SELECT * FROM bill_code_sequences WHERE 1 = 0;
  INSERT INTO bills SELECT * FROM bills WHERE 1 = 0;
  INSERT INTO bill_tags SELECT * FROM bill_tags WHERE 1 = 0;
  INSERT INTO payments SELECT * FROM payments WHERE 1 = 0;
  INSERT INTO ocr_tasks SELECT * FROM ocr_tasks WHERE 1 = 0;
  INSERT INTO ocr_retry_commands SELECT * FROM ocr_retry_commands WHERE 1 = 0;
  INSERT INTO business_command_replays SELECT * FROM business_command_replays WHERE 1 = 0;
  INSERT INTO audit_events (
      tenant_id, actor_user_id, action, aggregate_type, aggregate_id,
      request_id, details, created_at)
    SELECT 0, NULL, 'VERIFY', 'VERIFY', '0', NULL, NULL, CURRENT_TIMESTAMP(6)
    WHERE 1 = 0;
  UPDATE auth_sessions SET revoked_at = revoked_at WHERE 1 = 0;
  UPDATE admin_sessions SET revoked_at = revoked_at WHERE 1 = 0;
  UPDATE tenants SET status = status, updated_at = updated_at WHERE 1 = 0;
  UPDATE users SET status = status, updated_at = updated_at WHERE 1 = 0;
  UPDATE bill_code_sequences SET next_value = next_value WHERE 1 = 0;
  UPDATE bills SET updated_at = updated_at WHERE 1 = 0;
  UPDATE payments SET version = version WHERE 1 = 0;
  UPDATE ocr_tasks SET updated_at = updated_at WHERE 1 = 0;
  DELETE FROM bill_tags WHERE 1 = 0;"

deny demeter_api "DDL" "CREATE TABLE forbidden_api_ddl (id BIGINT PRIMARY KEY)"
deny demeter_api "scheduler state" "SELECT * FROM shedlock LIMIT 0"
deny demeter_api "maintenance run insertion" "INSERT INTO maintenance_runs SELECT * FROM maintenance_runs WHERE 1 = 0"
deny demeter_api "maintenance run update" "UPDATE maintenance_runs SET status = status WHERE 1 = 0"
deny demeter_api "maintenance run deletion" "DELETE FROM maintenance_runs WHERE 1 = 0"
deny demeter_api "session deletion" "DELETE FROM auth_sessions WHERE 1 = 0"
deny demeter_api "ledger deletion" "DELETE FROM payments WHERE 1 = 0"
deny demeter_api "audit deletion" "DELETE FROM audit_events WHERE 1 = 0"
deny demeter_api "idempotency retention deletion" "DELETE FROM business_command_replays WHERE 1 = 0"
deny demeter_api "admin idempotency retention deletion" "DELETE FROM admin_command_replays WHERE 1 = 0"
deny demeter_api "admin session deletion" "DELETE FROM admin_sessions WHERE 1 = 0"

allow demeter_worker "OCR queue processing" "
  SELECT COUNT(*) FROM flyway_schema_history;
  SELECT COUNT(*) FROM ocr_tasks;
  UPDATE ocr_tasks SET updated_at = updated_at WHERE 1 = 0;
  INSERT INTO audit_events (
      tenant_id, actor_user_id, action, aggregate_type, aggregate_id,
      request_id, details, created_at)
    SELECT 0, NULL, 'VERIFY', 'VERIFY', '0', NULL, NULL, CURRENT_TIMESTAMP(6)
    WHERE 1 = 0;"

deny demeter_worker "DDL" "CREATE TABLE forbidden_worker_ddl (id BIGINT PRIMARY KEY)"
deny demeter_worker "bill reads" "SELECT * FROM bills LIMIT 0"
deny demeter_worker "session reads" "SELECT * FROM auth_sessions LIMIT 0"
deny demeter_worker "OCR task insertion" "
  INSERT INTO ocr_tasks SELECT * FROM ocr_tasks WHERE 1 = 0"
deny demeter_worker "OCR task deletion" "DELETE FROM ocr_tasks WHERE 1 = 0"
deny demeter_worker "audit reads" "SELECT * FROM audit_events LIMIT 0"
deny demeter_worker "scheduler state" "SELECT * FROM shedlock LIMIT 0"
deny demeter_worker "maintenance run state" "SELECT * FROM maintenance_runs LIMIT 0"

allow demeter_maintenance "retention, lifecycle and reconciliation" "
  SELECT COUNT(*) FROM flyway_schema_history;
  SELECT COUNT(*) FROM shedlock;
  SELECT COUNT(*) FROM maintenance_runs;
  SELECT COUNT(*) FROM auth_sessions;
  SELECT COUNT(*) FROM admin_sessions;
  SELECT COUNT(*) FROM business_command_replays;
  SELECT COUNT(*) FROM admin_command_replays;
  SELECT COUNT(*) FROM ocr_retry_commands;
  SELECT COUNT(*) FROM ocr_tasks;
  SELECT COUNT(*) FROM bills;
  SELECT COUNT(*) FROM payments;
  INSERT INTO shedlock SELECT * FROM shedlock WHERE 1 = 0;
  INSERT INTO maintenance_runs SELECT * FROM maintenance_runs WHERE 1 = 0;
  UPDATE shedlock SET lock_until = lock_until WHERE 1 = 0;
  UPDATE maintenance_runs SET status = status WHERE 1 = 0;
  UPDATE ocr_tasks SET updated_at = updated_at WHERE 1 = 0;
  DELETE FROM auth_sessions WHERE 1 = 0;
  DELETE FROM admin_sessions WHERE 1 = 0;
  DELETE FROM business_command_replays WHERE 1 = 0;
  DELETE FROM admin_command_replays WHERE 1 = 0;
  DELETE FROM ocr_retry_commands WHERE 1 = 0;"

allow demeter_maintenance "maintenance run retention" "DELETE FROM maintenance_runs WHERE 1 = 0"

deny demeter_maintenance "DDL" "CREATE TABLE forbidden_maintenance_ddl (id BIGINT PRIMARY KEY)"
deny demeter_maintenance "tenant reads" "SELECT * FROM tenants LIMIT 0"
deny demeter_maintenance "user reads" "SELECT * FROM users LIMIT 0"
deny demeter_maintenance "bill mutation" "UPDATE bills SET updated_at = updated_at WHERE 1 = 0"
deny demeter_maintenance "ledger mutation" "UPDATE payments SET version = version WHERE 1 = 0"
deny demeter_maintenance "OCR task insertion" "
  INSERT INTO ocr_tasks SELECT * FROM ocr_tasks WHERE 1 = 0"
deny demeter_maintenance "OCR task deletion" "DELETE FROM ocr_tasks WHERE 1 = 0"
deny demeter_maintenance "audit access" "SELECT * FROM audit_events LIMIT 0"

allow demeter_migrator "schema migration DDL" "
  CREATE TABLE role_verification_migrator (id BIGINT PRIMARY KEY);
  ALTER TABLE role_verification_migrator ADD COLUMN verified_at TIMESTAMP NULL;
  DROP TABLE role_verification_migrator;"
deny demeter_migrator "system schema access" "SELECT * FROM mysql.user LIMIT 0"

echo "Database role permissions verified."

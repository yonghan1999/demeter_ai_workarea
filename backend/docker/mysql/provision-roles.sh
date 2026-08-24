#!/bin/sh
set -eu

: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"
: "${DB_API_PASSWORD:?DB_API_PASSWORD is required}"
: "${DB_WORKER_PASSWORD:?DB_WORKER_PASSWORD is required}"
: "${DB_MAINTENANCE_PASSWORD:?DB_MAINTENANCE_PASSWORD is required}"
: "${DB_MIGRATOR_PASSWORD:?DB_MIGRATOR_PASSWORD is required}"

mysql_host="${MYSQL_HOST:-mysql}"
mysql_port="${MYSQL_PORT:-3306}"
ssl_mode="${MYSQL_SSL_MODE:-DISABLED}"
db_name="${DB_NAME:-demeter}"
mode="${1:-grants}"

case "$db_name" in
  ''|*[!A-Za-z0-9_]*)
    echo "DB_NAME must contain only letters, numbers and underscores." >&2
    exit 64
    ;;
esac

validate_password() {
  case "$1" in
    ''|*[!A-Za-z0-9._~\-]*)
      echo "Database role passwords may contain only letters, numbers, '.', '_', '~' and '-'." >&2
      exit 64
      ;;
  esac
}

validate_password "$DB_API_PASSWORD"
validate_password "$DB_WORKER_PASSWORD"
validate_password "$DB_MAINTENANCE_PASSWORD"
validate_password "$DB_MIGRATOR_PASSWORD"

mysql_cmd() {
  if [ -n "${MYSQL_SSL_CA:-}" ]; then
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql \
      --protocol=tcp \
      --host="$mysql_host" \
      --port="$mysql_port" \
      --user=root \
      --ssl-mode="$ssl_mode" \
      --ssl-ca="$MYSQL_SSL_CA" \
      "$@"
  else
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql \
      --protocol=tcp \
      --host="$mysql_host" \
      --port="$mysql_port" \
      --user=root \
      --ssl-mode="$ssl_mode" \
      "$@"
  fi
}

case "$mode" in
  bootstrap)
    mysql_cmd <<SQL
CREATE USER IF NOT EXISTS 'demeter_api'@'%' IDENTIFIED BY '$DB_API_PASSWORD';
ALTER USER 'demeter_api'@'%' IDENTIFIED BY '$DB_API_PASSWORD';
CREATE USER IF NOT EXISTS 'demeter_worker'@'%' IDENTIFIED BY '$DB_WORKER_PASSWORD';
ALTER USER 'demeter_worker'@'%' IDENTIFIED BY '$DB_WORKER_PASSWORD';
CREATE USER IF NOT EXISTS 'demeter_maintenance'@'%' IDENTIFIED BY '$DB_MAINTENANCE_PASSWORD';
ALTER USER 'demeter_maintenance'@'%' IDENTIFIED BY '$DB_MAINTENANCE_PASSWORD';
CREATE USER IF NOT EXISTS 'demeter_migrator'@'%' IDENTIFIED BY '$DB_MIGRATOR_PASSWORD';
ALTER USER 'demeter_migrator'@'%' IDENTIFIED BY '$DB_MIGRATOR_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES, TRIGGER
  ON \`$db_name\`.* TO 'demeter_migrator'@'%';
SQL
    ;;
  grants)
    mysql_cmd <<SQL
REVOKE ALL PRIVILEGES, GRANT OPTION FROM
    'demeter_api'@'%', 'demeter_worker'@'%',
    'demeter_maintenance'@'%', 'demeter_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES, TRIGGER
    ON \`$db_name\`.* TO 'demeter_migrator'@'%';
SQL
    sed "s/__DB_NAME__/$db_name/g" /provision/role-grants.sql | mysql_cmd
    ;;
  *)
    echo "Unknown provisioning mode: $mode (expected bootstrap or grants)." >&2
    exit 64
    ;;
esac

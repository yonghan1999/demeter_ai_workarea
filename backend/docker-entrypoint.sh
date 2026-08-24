#!/bin/sh
set -eu

mode="${1:-api}"

run_application() {
  role="$1"
  shift
  export DEMETER_RUNTIME_ROLE="$role"
  exec java -jar /app/app.jar "$@"
}

case "$mode" in
  api)
    shift || true
    run_application API "$@"
    ;;
  worker)
    shift || true
    export OCR_WORKER_ENABLED=true
    run_application WORKER "$@"
    ;;
  maintenance)
    shift || true
    export MAINTENANCE_ENABLED=true
    run_application MAINTENANCE "$@"
    ;;
  migrate)
    if [ "$#" -ne 1 ]; then
      echo "The migrate mode does not accept additional arguments." >&2
      exit 64
    fi
    exec java \
      -Dloader.main=com.demeter.backend.migration.DatabaseMigrationMain \
      -cp /app/app.jar \
      org.springframework.boot.loader.launch.PropertiesLauncher
    ;;
  *)
    echo "Unknown startup mode: $mode (expected api, worker, maintenance, or migrate)." >&2
    exit 64
    ;;
esac

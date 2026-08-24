#!/bin/sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

log() {
  printf '[preflight] %s\n' "$1"
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf '[preflight] missing required command: %s\n' "$1" >&2
    exit 127
  fi
}

run_java_checks() {
  log "running Maven verify"
  (cd "$ROOT_DIR" && ./mvnw --batch-mode --no-transfer-progress verify)
}

run_static_checks() {
  log "checking shell scripts"
  sh -n "$ROOT_DIR/docker-entrypoint.sh"
  sh -n "$ROOT_DIR/docker/mysql/provision-roles.sh"
  sh -n "$ROOT_DIR/docker/mysql/verify-role-grants.sh"

  log "checking release documentation for stale production wording"
  if grep -R -n -E '永久删除|status": 501|启动时执行迁移|Flyway 在启动时|尚未接入微信登录|仍应在 CI 中增加' \
      "$ROOT_DIR/README.md" "$ROOT_DIR/OPERATIONS.md" "$ROOT_DIR/RELEASE_CHECKLIST.md"; then
    printf '[preflight] stale release wording found\n' >&2
    exit 1
  fi

  log "checking production environment template"
  test -f "$ROOT_DIR/.env.production.example"
  if grep -n 'sslMode=DISABLED' "$ROOT_DIR/.env.production.example"; then
    printf '[preflight] production environment template disables TLS\n' >&2
    exit 1
  fi
  grep -q '^FLYWAY_ENABLED=false$' "$ROOT_DIR/.env.production.example"
  grep -q '^SPRINGDOC_API_DOCS_ENABLED=false$' "$ROOT_DIR/.env.production.example"
  grep -q '^SPRINGDOC_SWAGGER_UI_ENABLED=false$' "$ROOT_DIR/.env.production.example"
  grep -q '^DATABASE_MINIMUM_SCHEMA_VERSION=18$' "$ROOT_DIR/.env.production.example"

  log "checking local secret files are not staged for release"
  if git -C "$ROOT_DIR/.." ls-files --error-unmatch .env .env.production backend/.env backend/.env.production >/dev/null 2>&1; then
    printf '[preflight] tracked local secret environment file found\n' >&2
    exit 1
  fi
  if find "$ROOT_DIR/.." -maxdepth 2 \( -name '.env' -o -name '.env.production' \) \
      ! -path "$ROOT_DIR/.env.example" \
      ! -path "$ROOT_DIR/.env.production.example" \
      -print -quit | grep -q .; then
    printf '[preflight] local secret environment file exists; move real secrets to the deployment secret store\n' >&2
    exit 1
  fi
}

run_docker_checks() {
  if ! command -v docker >/dev/null 2>&1; then
    log "Docker is not available; skipping Docker checks"
    return
  fi

  log "validating Docker Compose configuration"
  (cd "$ROOT_DIR" && docker compose config >/dev/null)

  if [ "${PREFLIGHT_BUILD_IMAGE:-false}" = "true" ]; then
    log "building production image"
    (cd "$ROOT_DIR" && docker build -t demeter-backend:preflight .)

    log "checking production image identity and healthcheck"
    user="$(docker image inspect demeter-backend:preflight --format '{{.Config.User}}')"
    if [ "$user" != "10001:10001" ]; then
      printf '[preflight] expected image user 10001:10001, got %s\n' "$user" >&2
      exit 1
    fi
    healthcheck="$(docker image inspect demeter-backend:preflight --format '{{json .Config.Healthcheck.Test}}')"
    if [ "$healthcheck" = "null" ]; then
      printf '[preflight] image healthcheck is missing\n' >&2
      exit 1
    fi
  fi
}

run_live_checks() {
  if [ -z "${PREFLIGHT_READINESS_URL:-}" ]; then
    log "PREFLIGHT_READINESS_URL is not set; skipping live readiness check"
    return
  fi
  require_command curl

  log "checking live readiness endpoint"
  body="$(curl --fail --silent "$PREFLIGHT_READINESS_URL")"
  printf '%s' "$body" | grep '"status":"UP"' >/dev/null
  printf '%s' "$body" | grep '"databaseSchema"' >/dev/null
  printf '%s' "$body" | grep '"roleReadiness"' >/dev/null

  if [ -n "${PREFLIGHT_MANAGEMENT_INFO_URL:-}" ]; then
    status="$(curl --silent --output /dev/null --write-out '%{http_code}' "$PREFLIGHT_MANAGEMENT_INFO_URL")"
    if [ "$status" != "401" ]; then
      printf '[preflight] expected unauthenticated management info status 401, got %s\n' "$status" >&2
      exit 1
    fi
  fi
}

main() {
  run_java_checks
  run_static_checks
  run_docker_checks
  run_live_checks
  log "release preflight completed"
}

main "$@"

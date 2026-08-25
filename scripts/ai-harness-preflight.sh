#!/bin/sh
set -eu

# Read-only policy checks used by humans and CI before an AI-assisted change is handed off.
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
BASE_REF="${1:-HEAD}"
CHANGED_FILES="$(mktemp)"
trap 'rm -f "$CHANGED_FILES"' EXIT

log() {
  printf '[ai-harness] %s\n' "$1"
}

fail() {
  printf '[ai-harness] ERROR: %s\n' "$1" >&2
  exit 1
}

is_sensitive_path() {
  case "$1" in
    .env|.env.*|*/.env|*/.env.*|project.private.config.json|*.pem|*.key|*.p12|*/secrets/*|*credentials*.json|*secret*.json)
      case "$1" in
        *.example) return 1 ;;
        *) return 0 ;;
      esac
      ;;
    *) return 1 ;;
  esac
}

require_git_ref() {
  git -C "$ROOT_DIR" rev-parse --verify --quiet "${BASE_REF}^{commit}" >/dev/null \
    || fail "base ref does not resolve to a commit: $BASE_REF"
}

collect_changed_files() {
  git -C "$ROOT_DIR" diff --name-only --diff-filter=ACMR "$BASE_REF" -- > "$CHANGED_FILES"
  git -C "$ROOT_DIR" ls-files --others --exclude-standard >> "$CHANGED_FILES"
  sort -u "$CHANGED_FILES" -o "$CHANGED_FILES"
}

check_sensitive_paths() {
  log "checking tracked and changed sensitive paths"
  git -C "$ROOT_DIR" ls-files | while IFS= read -r path; do
    if is_sensitive_path "$path"; then
      printf '%s\n' "$path"
      exit 1
    fi
  done > "$CHANGED_FILES.sensitive" || {
    paths="$(tr '\n' ' ' < "$CHANGED_FILES.sensitive")"
    rm -f "$CHANGED_FILES.sensitive"
    fail "sensitive file is tracked: $paths"
  }
  rm -f "$CHANGED_FILES.sensitive"

  while IFS= read -r path; do
    if is_sensitive_path "$path"; then
      fail "sensitive file is included in the working change: $path"
    fi
  done < "$CHANGED_FILES"
}

check_append_only_migrations() {
  log "checking Flyway migrations are append-only"
  invalid="$(git -C "$ROOT_DIR" diff --name-status "$BASE_REF" -- \
    backend/src/main/resources/db/migration backend/src/main/java/db/migration | grep -v '^A' || true)"
  if [ -n "$invalid" ]; then
    fail "existing Flyway migrations must not be modified or removed:\n$invalid"
  fi
}

check_miniprogram_boundaries() {
  if ! grep -q '^miniprogram/' "$CHANGED_FILES"; then
    return
  fi

  log "checking Mini Program page boundaries"
  find "$ROOT_DIR/miniprogram/pages" -path '*/index.js' -type f | while IFS= read -r page; do
    if ! grep -q 'withSystemLayout' "$page"; then
      fail "Mini Program page bypasses Safe Area layout helper: ${page#$ROOT_DIR/}"
    fi
    if grep -q "mock-store" "$page"; then
      fail "Mini Program page imports mock-store directly: ${page#$ROOT_DIR/}"
    fi
  done
}

check_business_chain_boundaries() {
  while IFS= read -r path; do
    case "$path" in
      backend/src/main/java/com/demeter/backend/bill/application/*Service.java|\
      backend/src/main/java/com/demeter/backend/payment/application/*Service.java|\
      backend/src/main/java/com/demeter/backend/ocr/application/OcrTaskService.java|\
      backend/src/main/java/com/demeter/backend/ocr/application/OcrTaskWorker.java)
        file="$ROOT_DIR/$path"
        if [ -f "$file" ] && ! grep -q 'BusinessChainExecutor' "$file"; then
          fail "business use case no longer declares BusinessChainExecutor: $path"
        fi
        ;;
    esac
  done < "$CHANGED_FILES"
}

print_scope() {
  backend_changed=false
  miniprogram_changed=false
  high_risk=false

  while IFS= read -r path; do
    case "$path" in
      backend/*) backend_changed=true ;;
      miniprogram/*) miniprogram_changed=true ;;
    esac
    case "$path" in
      backend/src/main/java/com/demeter/backend/security/*|\
      backend/src/main/java/com/demeter/backend/config/*|\
      backend/src/main/java/com/demeter/backend/common/chain/*|\
      backend/src/main/java/com/demeter/backend/payment/*|\
      backend/src/main/java/com/demeter/backend/ocr/*|\
      backend/src/main/resources/db/migration/*|\
      backend/src/main/java/db/migration/*|\
      backend/src/main/resources/application-prod.yml|\
      backend/compose.yml|backend/Dockerfile|backend/docker/*)
        high_risk=true
        ;;
    esac
  done < "$CHANGED_FILES"

  log "scope: backend=$backend_changed miniprogram=$miniprogram_changed high-risk=$high_risk"
  if [ "$backend_changed" = true ]; then
    log "backend verification: ./mvnw --batch-mode --no-transfer-progress clean verify"
  fi
  if [ "$high_risk" = true ]; then
    log "high-risk verification: backend/scripts/release-preflight.sh and focused boundary tests"
  fi
  if [ "$miniprogram_changed" = true ]; then
    log "Mini Program verification: compile in WeChat Developer Tools; check loading, empty, failure, save-in-progress, and Safe Area states"
  fi
}

main() {
  require_git_ref
  log "checking whitespace against $BASE_REF"
  git -C "$ROOT_DIR" diff --check "$BASE_REF" --
  collect_changed_files
  check_sensitive_paths
  check_append_only_migrations
  check_miniprogram_boundaries
  check_business_chain_boundaries
  print_scope
  log "policy checks passed"
}

main "$@"

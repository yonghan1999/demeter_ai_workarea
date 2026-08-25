#!/bin/sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
BASE_REF="${1:-HEAD}"

sh "$ROOT_DIR/scripts/ai-harness-preflight.sh" "$BASE_REF"

changed_files="$(git -C "$ROOT_DIR" diff --name-only --diff-filter=ACMR "$BASE_REF" --; git -C "$ROOT_DIR" ls-files --others --exclude-standard)"

if printf '%s\n' "$changed_files" | grep -q '^backend/'; then
  if printf '%s\n' "$changed_files" | grep -Eq '^backend/(src/main/java/com/demeter/backend/(security|config|common/chain|payment|ocr)/|src/main/resources/db/migration/|src/main/java/db/migration/|src/main/resources/application-prod.yml|compose.yml|Dockerfile|docker/)'; then
    (cd "$ROOT_DIR/backend" && ./scripts/release-preflight.sh)
  else
    (cd "$ROOT_DIR/backend" && ./mvnw --batch-mode --no-transfer-progress clean verify)
  fi
fi

printf '%s\n' '[ai-harness] automated verification completed; record any required WeChat Developer Tools evidence in the delivery summary.'

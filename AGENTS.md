# Demeter AI Coding Instructions

This repository contains a production-oriented WeChat Mini Program and a Java
backend. Treat these instructions as the engineering contract for Codex.

## Project map

- `miniprogram/`: WeChat Mini Program. It currently uses mock data through
  `miniprogram/services/bill-service.js`.
- `backend/`: Java 17, Spring Boot, MySQL, Flyway, JPA backend.
- `backend/src/main/java/com/demeter/backend/common/chain/`: the business
  responsibility-chain infrastructure. Business use cases must execute through
  a chain and handlers run in declared order.
- `backend/src/main/java/com/demeter/backend/ocr/spi/`: OCR provider boundary.
  The Alibaba Cloud handwritten-bill adapter is intentionally not implemented.

## Working agreement

- Inspect the relevant files and current Git status before editing.
- Keep changes scoped to the user's request. Do not rewrite unrelated user
  changes, generated files, migrations, or formatting.
- Do not commit, push, force-push, create tags, reset, clean, or delete files
  outside the requested scope without explicit user approval.
- Do not read, print, copy, or commit secrets. This includes `.env` files,
  private WeChat credentials, database passwords, cloud credentials, tokens,
  private keys, certificates, and production data. Use example files and
  redacted values instead.
- Do not add a production dependency, external service, or network integration
  without explaining its operational impact and updating the relevant docs.
- Never weaken authentication, tenant isolation, idempotency, audit logging,
  rate limiting, migration validation, health checks, or production startup
  validation to make a test pass.

## Implementation rules

- Follow existing Spring, JPA, validation, error, security, and test patterns.
- Route backend business operations through `BusinessChainExecutor`; keep
  validation, authorization, persistence, side effects, and response mapping
  as explicit ordered handlers.
- Preserve tenant scoping on every user-owned read and write.
- Use append-only Flyway migrations. Production runtime processes do not run
  Flyway; the dedicated migrator does. Never use Hibernate schema creation or
  update in production.
- Keep OCR vendor code behind
  `HandwrittenBillOcrProvider`. Translate vendor failures into the existing
  stable failure categories and never persist vendor response bodies or secrets.
- Keep the Mini Program UI within the Safe Area and preserve the Figma-derived
  layout. Keep page code behind the service layer and do not couple pages to
  mock-store internals.
- Prefer structured parsers, typed configuration, and existing helpers over
  string-based shortcuts. Keep comments short and explain only non-obvious
  decisions.

## Verification

Classify the changed paths with `sh scripts/ai-harness-preflight.sh` before
hand-off. Use `sh scripts/ai-harness-verify.sh` when its automated scope
matches the change; it selects Maven verification or release preflight for
backend work. Record the required WeChat Developer Tools evidence separately
rather than claiming an automated Mini Program check.

Run the smallest relevant checks during iteration, then the full checks before
hand-off:

```bash
cd backend
./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/release-preflight.sh
docker compose config
```

For changes to JavaScript or Mini Program files, use WeChat Developer Tools to
compile and exercise the affected flow. At minimum check loading, empty,
failure, save-in-progress, and Safe Area states where applicable.

For changes to production configuration, migrations, security, payments, OCR,
or shared chain infrastructure, also inspect the diff carefully and run the
relevant focused tests. Do not claim a live deployment check unless a real
deployment URL was tested.

The developer machine has 8 GB RAM. Keep Docker and test execution serial and
lightweight; do not start the full Compose application stack unless the user
explicitly asks for it.

## Delivery protocol

Before reporting completion:

1. Check `git status --short` and `git diff --check`.
2. Summarize behavior changes, verification performed, and any remaining
   environment-dependent checks.
3. Ask before any remote Git operation or irreversible action.

When a task conflicts with these rules, pause and explain the conflict rather
than silently weakening the production boundary.

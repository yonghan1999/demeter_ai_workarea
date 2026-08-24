# Demeter Backend Release Checklist

Use this checklist for every production release. Do not treat a CI pass as a
replacement for target-environment verification.

## 1. CI gates

- [ ] `scripts/release-preflight.sh` passed in the release environment.
- [ ] `clean verify` passed.
- [ ] MySQL 8.4 Testcontainers integration tests passed.
- [ ] Gitleaks scan passed.
- [ ] OWASP Dependency Check passed.
- [ ] Docker image build passed.
- [ ] Trivy image scan passed.
- [ ] CI smoke test verified TLS MySQL migration, role grants, API readiness,
      Maintenance readiness, and expected Worker startup rejection without an
      OCR provider.

## 2. Target environment

- [ ] Production secrets are loaded from the approved secret manager.
- [ ] No `.env`, database password, WeChat secret, or cloud credential is baked
      into the image or committed to Git.
- [ ] MySQL TLS uses `sslMode=VERIFY_IDENTITY` and the certificate name matches
      the database DNS name.
- [ ] Database accounts are role-specific:
      `demeter_api`, `demeter_worker`, `demeter_maintenance`,
      `demeter_migrator`.
- [ ] API, Worker, Maintenance, and Migrator use separate credentials.
- [ ] API, Worker, and Maintenance have `FLYWAY_ENABLED=false`.
- [ ] Migrator is the only process allowed to run Flyway.
- [ ] Management endpoints are reachable only from the private network or
      localhost.
- [ ] `MANAGEMENT_ADDRESS` binds to a loopback address; public access goes
      through private network controls only.
- [ ] `/actuator/info` and `/actuator/prometheus` require the management token.
- [ ] Optional live preflight was run with `PREFLIGHT_READINESS_URL` and
      `PREFLIGHT_MANAGEMENT_INFO_URL`.

## 3. Migration and rollout

- [ ] Database snapshot or restore point was created before migration.
- [ ] The same migration version was tested against a staging or shadow
      database.
- [ ] Migrator completed successfully.
- [ ] `/actuator/health/readiness` includes `databaseSchema` and
      `roleReadiness`, and returns `UP`.
- [ ] Roll out one API instance first.
- [ ] Confirm WeChat login, bill list, bill creation, payment creation, soft
      delete, restore, and OCR upload contract.
- [ ] Expand traffic gradually while watching error rate, P95 latency, Hikari
      pool usage, MySQL lock waits, and OCR queue depth.

## 4. Rollback

- [ ] The previous application image is compatible with the current schema.
- [ ] Rollback is application-only unless a forward database fix is approved.
- [ ] Idempotency keys must be reused for retried client operations.
- [ ] Incident notes include `X-Request-Id` values, deployment version, schema
      version, and rollback decision.

## 5. Backup and recovery

- [ ] PITR is enabled and recent binlogs are available.
- [ ] Backup encryption and retention were verified.
- [ ] OCR object storage versioning and server-side encryption are enabled.
- [ ] The latest restore drill recorded restore point, recovery duration,
      validation scope, and approver.
- [ ] Recovery validation covers tenants, users, sessions, bills, payments,
      audit events, OCR tasks, and command replay records.

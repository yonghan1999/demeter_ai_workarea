# Demeter Copilot Instructions

Use the repository's shared engineering contract in [`AGENTS.md`](../AGENTS.md)
for architecture, security, testing, and delivery rules. In particular:

- Keep backend business flows in the responsibility-chain infrastructure.
- Preserve tenant isolation, idempotency, audit logging, rate limiting, and
  production readiness checks.
- Keep OCR vendor integrations behind the provider SPI and out of business
  logic.
- Never expose or request secrets from `.env` files, private credentials,
  tokens, keys, certificates, or production data.
- Make focused changes, run the relevant tests, and inspect the final diff.

# DECISIONS.md — judgement calls and why

Where this file and the PRD/BUILD_INSTRUCTIONS touch, the reasoning is here.
Stack and scope were fixed by the brief and were not changed.

1. **Tests run against the Docker Compose Postgres (`empdb_test`) instead of
   Testcontainers.** The installed Docker Desktop (Engine 29, 2026) returns an
   undocumented 400 to the docker-java handshake bundled with Testcontainers
   1.21.3 — the latest release — after trying both Windows named pipes. The
   tests still run against real PostgreSQL with the real schema, security chain
   and encryption. Revisit when Testcontainers ships Engine-29 support; BaseIT
   reads `TEST_DB_URL`/`TEST_DB_USERNAME`/`TEST_DB_PASSWORD` so the swap is a
   one-line change.

2. **MinIO removed from docker-compose for now.** The `minio/minio` image could
   not be pulled in this environment (Docker Hub denied; quay.io 401). No
   Phase 1–2 feature stores files: CSV uploads are parsed in-memory. File
   storage arrives with the Phase 3 documents module — re-add the service then
   (the `STORAGE_*` env vars are already in `.env.example`).

3. **Individual money fields are AES-256-GCM encrypted and stored as `bytea`;
   aggregation happens in Java.** At the stated scale (a few hundred employees
   per company) loading a company's rows and aggregating with BigDecimal is
   instant, keeps one code path, and satisfies "encrypt salary fields at rest"
   without a SQL-side key ceremony. Tier/role salary *ranges* are configuration,
   not individual data, and stay `numeric`.

4. **Employee email uniqueness is per company; sign-in email is globally
   unique.** Two companies can both employ `dev@example.com`; a person signs in
   with one account. Login therefore needs no company selector.

5. **Variable-pay defaults are stored on the job role (JSON) and applied as a
   copy when relevant; tier-level defaults were dropped in v1.** A resolution
   engine (role vs tier vs employee overrides) is real complexity for a feature
   the CFO can express by editing employees. Recorded as the known ceiling.

6. **Import is CSV-only** ("XLSX if easy" — Apache POI is a heavy dependency for
   one convenience). The error message tells the user to save as CSV. The import
   re-uploads the file for preview and apply, keeping the endpoint stateless.

7. **Undo imports keeps a before-image JSON snapshot per import batch.** Undo is
   refused when a created employee already has pay records (raise history or
   components) — deleting those would silently break the append-only salary
   history guarantee.

8. **Salary history is append-only; mistakes are fixed with reversing entries.**
   The reverse endpoint refuses to reverse an *initial* salary entry (old = 0);
   the CFO sets a new salary instead.

9. **Payroll-summary report reconstructs monthly base cost from salary history;
   historical variable-pay is shown at current run-rate only.** Components are
   not versioned in v1; a point-in-time variable-pay ledger is Phase-2.5 work if
   the CFO needs it.

10. **In-memory login rate limiter** — keyed by email+IP, per node. Single-node
    deployment assumed for v1; move counters to Postgres/Redis when running more
    than one API instance.

11. **`spring.mail.from` is used for outbound mail; when mail sending fails in
    dev the reset link is logged** (Mailpit normally catches it). Production
    profile requires working mail — reset links are never logged there.

12. **Refresh-cookie CSRF guard**: refresh/logout require `X-Requested-With:
    XMLHttpRequest` (cross-site requests can't set custom headers) plus a
    `SameSite=Strict` cookie. A synchronizer-token layer was not added because
    all other API calls are bearer-token based.

13. **Job roles auto-create during CSV import when missing** (they are
    low-risk labels); **tiers never auto-create** (they carry salary-range
    semantics the CFO should define) — the row errors instead, in plain language.

14. **Next.js 15.5 / React 19 / Tailwind 4 pinned to major versions current at
    build time; Recharts 2.x** (3.x migration is optional churn for this UI).

15. **TOTP two-factor was skipped** — the brief marks it optional "(A)"; it needs
    a QR/enrollment UI worth doing properly. Listed in PROGRESS.md as deferred.

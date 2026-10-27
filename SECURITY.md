# Security — controls implemented and how to rotate secrets

## October 11 additions

- Admin and CFO can assign all built-in roles, create linked accounts and generate temporary credentials. Generated passwords require a first-login change. Role changes and deactivation take effect on the next API request through fresh database authorization.
- Password reset, password change and regenerated credentials increment a credential version, immediately invalidating existing access tokens as well as revoking refresh tokens. Refresh tokens are single-use and locked during rotation.
- Sensitive public endpoints and authenticated writes have bounded, per-node rate limits. Move these counters to shared storage before multiple API replicas.
- HR record payloads and personnel-file contents are encrypted with AES-GCM. File download links expire after 60 seconds and recheck the issuing account and direct-manager permissions. File uploads are limited to 5 MB and validated formats.
- HR/Admin and direct-manager scopes are checked by the API. CFO account administration does not automatically grant access to other employees’ confidential personnel files.
- Reset links and generated Spring Security fallback credentials are no longer printed in application logs. Synthetic test logs are not production logging configuration.

The HR retention setting records the company policy; automatic deletion is not enabled. Confirm jurisdiction-specific retention and legal holds before configuring a purge. Database disks, backups, SMTP, TLS termination and secret management remain deployment responsibilities.

## Authentication

- Passwords hashed with **BCrypt (cost 12)**; policy: ≥10 chars, letter + number, ≠ email.
- **JWT access tokens** (15 min, HS256) held in browser memory only — never in
  localStorage.
- **Refresh tokens**: opaque 256-bit random strings, **stored hashed (SHA-256)** in
  `refresh_tokens`, delivered as `httpOnly, SameSite=Strict, Path=/api/auth`
  cookies, **rotated on every use**, revoked on logout, password change/reset.
  The refresh/logout endpoints require a custom `X-Requested-With` header — a
  CSRF guard (cross-site requests cannot set custom headers); the API is
  otherwise bearer-token based.
- **Brute force**: in-memory rate limiter (5 failures / 15 min → 15-min lock,
  keyed email+IP) plus persistent per-user failure counters and `locked_until`.
  Generic "Email or password is incorrect" for both unknown email and wrong
  password (no account enumeration).
- **Password reset**: single-use, 30-minute, hashed tokens; all sessions revoked
  on reset. Mail is caught locally by Mailpit (http://localhost:8025).

## Authorization

- Tour completion is self-scoped tutorial metadata. Keys are allowlisted against
  registered pages and HR modules; prepared SQL appends atomically and idempotently.
  Callers cannot choose a user/company or replace arbitrary preference arrays.
  Existing JWT setup gates and authenticated-write rate limits apply. No tour
  endpoint grants access or changes credentials or business records.

- Every endpoint checks the **access role AND ownership/team** on the server;
  deny by default (`anyRequest().authenticated()` + method-level checks).
- **Salary data is encrypted at rest** (AES-256-GCM via JPA attribute converters,
  fresh IV per value) and visible only to Admin/CFO; HR only when the CFO
  enables `hr_can_see_salary`; managers/employees never see others' pay. Every
  view of compensation writes an audit entry (`COMPENSATION_VIEWED`).
- **Strict `company_id` isolation**: all queries filter by company; every table
  carries `company_id`. Proven by tests (company B gets 404/400 on company A's
  data).
- Raise approval/tier assignment: CFO/Finance approve; HR may propose.
- **Audit log**: append-only; records who, what, when, IP for every
  view/create/update/delete/import/export of compensation or personnel data and
  all auth events. Admin-only access.

## Data protection

- Money is `BigDecimal` in Java / encrypted `BYTEA` or `NUMERIC` in PostgreSQL —
  **no floating point** anywhere in money math.
- TLS in transit in production (HSTS header added on HTTPS; disabled responses
  over plain HTTP dev).
- No secrets in logs; errors are consistent JSON without stack traces;
  `include-stacktrace: never`.

## Web & API hardening

- Bean Validation on every input; Zod mirrors policy client-side.
- Parameterized queries only (Spring Data JPA / Flyway migrations).
- **CSV formula-injection protection** on export (dangerous cells prefixed `'`).
- Uploads: 5 MB limit, type-accepted CSV, parsed (never executed), UTF-8 BOM handling.
- CORS allowlist from `CORS_ORIGINS`; CSP `default-src 'none'; frame-ancestors
  'none'`; `X-Content-Type-Options: nosniff`; strict referrer policy; frames denied.
- Production profile (`prod`) **fails fast** if required secrets are missing or
  still equal the values shipped in `.env.example`.

## Portal setup and attendance controls

Finance review (20 October): HR raise preview/create/detail/cancel responses now
respect salary visibility, including the effective percentage derived from pay.
Raise details require an authorized finance/HR role. Tier assignments validate the
entire company-scoped active employee selection before writing. Band/role/component
inputs have server validation; report CSV month requests are bounded to 36.

First-login credentials cannot access business APIs before setup completes.
Demo OTP is opt-in, expires after ten minutes, has a sixty-second resend cooldown,
five verification attempts and account/IP request limits. Verification is consumed
once; credential changes revoke sessions. This mock is not email ownership proof.
Keep `DEMO_OTP_ENABLED=false` in production until a real provider is implemented.

Private onboarding profiles use authenticated field encryption and explicit
self/Admin/HR/CFO field permissions. Managers cannot read payment profiles.
Attendance uses server time and employee row locks; overlaps, self approval,
cross-company references and changes to submitted/approved weeks are rejected.
Audit records omit OTPs, passwords and bank account numbers.

Forwarded headers default to ignored (`FORWARD_HEADERS_STRATEGY=none`). Only enable
proxy forwarding behind an explicitly trusted edge that strips client headers.
In-memory IP throttles are per process; shared throttling is needed if deploying
multiple application instances. Account lockout remains persisted in PostgreSQL.

## Supply chain

- Dependencies pinned via the Spring Boot BOM; run `mvn ossindex:audit` or
  OWASP Dependency-Check and `npm audit` on your schedule — record findings in
  your tracker.

## Rotating secrets

All secrets live in `.env` (git-ignored). To rotate:

1. **`DATA_ENCRYPTION_KEY`** — ⚠️ rotating it makes existing encrypted salaries
   unreadable. To rotate for real, plan a migration: decrypt rows with the old
   key, re-encrypt with the new one, swap the env, restart. Generate with
   `openssl rand -base64 32`.
2. **`JWT_ACCESS_SECRET` / `JWT_REFRESH_SECRET`** — replace and restart; all
   access tokens die immediately (users re-login via refresh, which requires the
   old refresh secret — users re-enter passwords if both rotated).
3. **Database password** — change in Postgres (`ALTER USER emp WITH PASSWORD
   …`), then `DB_PASSWORD`/`POSTGRES_PASSWORD` in `.env`, restart.
4. After any rotation, confirm `docker compose --profile app up -d` starts clean
   and the health endpoint responds: `curl http://localhost:8080/actuator/health`.

## Production credential checklist (what you must supply)

| Variable | Where | Notes |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `.env` | managed PostgreSQL |
| `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET` | `.env` | `openssl rand -hex 32`, must differ from `.env.example` |
| `DATA_ENCRYPTION_KEY` | `.env` | `openssl rand -base64 32`; treat as irreplaceable — back it up |
| `MAIL_HOST/PORT/USERNAME/PASSWORD` or API key | `.env` | real SMTP provider |
| `CORS_ORIGINS`, `FRONTEND_URL` | `.env` | your real domain |
| TLS termination | host | reverse proxy / platform |

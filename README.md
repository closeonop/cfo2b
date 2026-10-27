# Employee Management & Compensation Platform

October 11 update: lifecycle modules, private personnel files, attendance, approvals and Admin/CFO credential management are implemented. The current PRD audit and demo instructions are in [the frontend feature audit](../emp%20mg1/FEATURE_AUDIT.md). `PROGRESS.md` contains the earlier delivery history; its original “not started” labels are superseded by this audit.

One record per employee. Built for a CFO first: import people from a CSV, set up
pay tiers, give a raise with a preview of the exact cost, and export payroll-ready
reports — without training, jargon, or spreadsheets breaking.

| Repo | What it is |
|---|---|
| **`emp mg2`** (this repo) | Spring Boot API, PostgreSQL schema, Docker Compose, tests, docs |
| **`emp mg1`** | Next.js + TypeScript frontend (see its own README) |

## Quick start (one command)

Requires Docker Desktop. Then, from this folder:

```bash
cp .env.example .env      # local dev secrets are already generated for you
docker compose up -d      # infra: Postgres (:5433), Mailpit (:8025), MinIO (:9000)
docker compose --profile app up -d   # + full app: API (:8080), frontend (:3000)
```

Open **http://localhost:3000**, click **Set up your company**, create the admin
account, and start adding employees.

For day-to-day development (hot reload) instead of the containers:

```bash
docker compose up -d db mailpit minio            # infra only
mvn spring-boot:run                              # API on :8080
cd "../emp mg1" && npm run dev                   # frontend on :3000
```

## Demo data (optional, never automatic)

With the API running:

```bash
node scripts/seed-demo.mjs
```

It creates a demo company (tiers, roles, 6 employees, variable pay, a raise) and
prints a CFO login you can sign in with. Safe to run repeatedly — every run makes
a fresh company.

## Sample files

- `samples/employees-sample.csv` — a ready import file (matches the downloadable template)

## Tests

```bash
mvn test
```

67 tests cover: auth flows (lockout, refresh rotation, password reset),
**the salary-visibility matrix** (CFO yes, HR optional, managers/employees no),
company isolation, CSV import/undo/export (including formula-injection escaping),
raise math (percent/fixed/rounding/tier caps/future-dated), salary-history
immutability, and AES-GCM encryption round-trips.

Tests run against the Docker Compose Postgres (`empdb_test` database — created
automatically if you start `db`; see `DECISIONS.md` for why this replaced
Testcontainers).

## Documentation

- [`USER_GUIDE.md`](USER_GUIDE.md) — for the CFO/HR: every main task, step by step
- [`SECURITY.md`](SECURITY.md) — implemented controls and how to rotate secrets
- [`DECISIONS.md`](DECISIONS.md) — every judgement call and its reason
- [`PROGRESS.md`](PROGRESS.md) — phase status and FR checklist

## Configuration

Guided tours use Flyway V6 (`users.tours_completed` JSONB). The authenticated
`GET /api/user/tours` and `POST /api/user/tours/{tourKey}/complete` operate only on
the current user. Login/refresh/session responses expose `toursCompleted`.
See the frontend's `WALKTHROUGHS.md` for the page inventory and extension guide.

PowerShell/Maven does not automatically read `.env`. Load its values into the process, and use `DB_URL=jdbc:postgresql://localhost:5433/empdb` and `STORAGE_ENDPOINT=http://localhost:9000` when running outside Docker. The compose defaults use container hostnames. Tests use the separate `empdb_test` database; set `TEST_DB_PASSWORD` to the local Postgres password. `TEST_STORAGE_ENDPOINT` defaults to `http://localhost:9000`.

The `operations` profile adds daily PostgreSQL custom-format backups in the `backups` Docker volume: `docker compose --profile operations up -d backup`. It retains snapshots; arrange encrypted off-machine copies, storage capacity monitoring and a restore drill before production. Personnel-file objects in `filedata` also need backups.

The optional `scripts/seed-demo.ps1` uses explicit synthetic fixtures for manual testing. Run it once against a fresh `empdb_demo` API, never a production database. Flyway itself does not seed any accounts or records.

All secrets come from the environment (`.env`, git-ignored). See
[`.env.example`](.env.example) — every local value was randomly generated and is
refused in production (`prod` profile fails fast on defaults). Production
credential needs are listed in `SECURITY.md`.

## Employee setup and time tracking

Admin creates a linked employee account with generated credentials in Accounts.
The first sign-in is restricted to setup: verify the demo email code, replace the
temporary password, save personal/payment details, then sign in again. Set
`DEMO_OTP_ENABLED=true` only for local demos; the fixed code is `123456`, no email
is sent, and it does not prove email ownership. The flag defaults to false. A real
verification provider must replace the mock before production onboarding.

Employees explicitly start, switch and finish work. Server timestamps feed the
same tasks, attendance records and weekly submissions used by managers and HR.
Time correction requests require approval and cannot alter frozen weeks. CFO sees
company totals and approved exports, without other employees' work notes.
Visible pages refresh every 30 seconds and on focus. Private profile fields are
encrypted and separately permission checked; they are excluded from directories.


## Stack

Spring Boot 3.5 (Java 21) · Spring Security + JWT · PostgreSQL + Flyway ·
AES-256-GCM field encryption · Apache Commons CSV · Next.js 15 / React 19 /
Tailwind 4 / shadcn-style UI · React Hook Form + Zod · TanStack Table · Recharts.

# Employee Management & Compensation Platform

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
docker compose up -d      # infra: Postgres (:5433), Mailpit (:8025)
docker compose --profile app up -d   # + full app: API (:8080), frontend (:3000)
```

Open **http://localhost:3000**, click **Set up your company**, create the admin
account, and start adding employees.

For day-to-day development (hot reload) instead of the containers:

```bash
docker compose up -d db mailpit                  # infra only
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

38 tests cover: auth flows (lockout, refresh rotation, password reset),
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

All secrets come from the environment (`.env`, git-ignored). See
[`.env.example`](.env.example) — every local value was randomly generated and is
refused in production (`prod` profile fails fast on defaults). Production
credential needs are listed in `SECURITY.md`.

## Stack

Spring Boot 3.5 (Java 21) · Spring Security + JWT · PostgreSQL + Flyway ·
AES-256-GCM field encryption · Apache Commons CSV · Next.js 15 / React 19 /
Tailwind 4 / shadcn-style UI · React Hook Form + Zod · TanStack Table · Recharts.

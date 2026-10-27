# PROGRESS.md

**Current status (2026-10-11):** the employee lifecycle, productivity, hiring and five approved portal additions are implemented. The [current feature audit](../emp%20mg1/FEATURE_AUDIT.md) supersedes the historical phase labels and checklist below. Production hosting, legal retention rules, performance benchmarking and off-machine restore verification remain operational work.

Status after this delivery run (2026-10-06). Phases follow BUILD_INSTRUCTIONS §5.

## Phase status

| Phase | Scope | Status |
|---|---|---|
| 0. Foundation | repo structure, Docker Compose, Flyway, auth, access roles, company setup, audit log, base UI + design system | **Done** |
| 1. CFO core | employee list (manual + CSV import/export/undo), job roles, tiers, salary history, raises with preview/approval, variable pay, total compensation views, dashboard, reports | **Done** |
| 2. Employee management (PRD) | personnel files, metrics & reviews, 1:1s & feedback, time & attendance, discipline | **Not started** (Phase 3 in BUILD_INSTRUCTIONS) |
| 3. Productivity | projects/tasks, recognition & rewards, development goals | Not started |
| 4. Hiring | job postings, candidate pipeline, onboarding | Not started |
| 5. Polish | accessibility pass, performance, docs | Partially done (a11y built in from the start; full pass pending) |

## FR checklist (PRD v2 + BUILD_INSTRUCTIONS additions)

Legend: ✅ Done · ⏭ Skipped with reason (tracked above) · 🔜 Next phase

**4.1 Auth & access**
- ✅ FR-1.1 Email+password login, JWT access + rotating refresh tokens
- ✅ FR-1.2 Role + ownership checks on every endpoint (tested)
- ✅ FR-1.3 Rate limiting + lockout on login (tested)
- ⏭ FR-1.4 Password reset by email — **done and tested via tokens; real SMTP provider needed for production** (Mailpit locally)

**4.2 Employee records**
- ✅ FR-2.1 Directory + profile (position/role, manager, team, start date, status)
- ⏭ FR-2.2/2.3/2.4 Confidential personnel files with uploads — 🔜 Phase 2 (needs MinIO/file storage; audit-logging of views already built)
- ✅ FR-2.5 No protected-class data is stored anywhere in v1

**4.3 Hiring** — 🔜 Phase 4 entirely (job postings, pipeline, scorecards, convert, onboarding)

**4.4 Measuring (performance)** — 🔜 Phase 2 (metrics per position, reviews, trend, evidence)

**4.5 Interacting** — 🔜 Phase 2 (1:1 logs, feedback with acknowledgement, ratio view, reminders, why-this-matters)

**4.6 Developing** — 🔜 Phase 3 (skills, goals, training, requests, team overview)

**4.7 Rewarding** — 🔜 Phase 3 (recognition, rewards linked to performance)

**4.8 Disciplining** — 🔜 Phase 2 (ordered policy, logged actions, next-step suggestion, employee's side, acknowledgement, consistency report)

**4.9 Time & attendance** — 🔜 Phase 2 (clock in/out, weekly hours, approval, CSV export)

**4.10 Projects & tasks** — 🔜 Phase 3

**BUILD_INSTRUCTIONS additions**
- ✅ FR-11.1 Employee list: manual add (short form), edit, deactivate, profile with comp history
- ✅ FR-11.2 CSV import: template, auto-column-detect + manual mapping, preview with row errors in plain language, duplicate skip/update, single-transaction apply, undo last import; strict file validation; formula-injection protection on import+export
- ✅ FR-11.3 CSV export (salary column only for viewers with pay access)
- ✅ FR-11.4 Tiers: create/edit/reorder/archive (tiers with people are archived, never deleted), manual + bulk + CSV assignment, salary-range warning (non-blocking), per-tier headcount/cost/average/range stats, tier suggestion from salary
- ✅ FR-11.5 Raises: one/many/whole tier; percent or fixed; effective date + reason; preview with old/new/total monthly+yearly cost and confirm; over-tier-max choice (cap/allow); configurable rounding; approval flow (HR proposes → CFO approves, CFO may apply directly); permanent salary history (append-only, reversing entries); future-dated raises applied by scheduled job
- ✅ FR-11.6 Job roles (name, default tier, default range) + built-in access roles with permission table
- ✅ FR-11.7 Base salary (monthly/annual configurable) + variable components (fixed/%/target; monthly/quarterly/yearly/one-time; start/end dates); total comp with breakdown; company cost by tier/role/team; exact decimal money
- ✅ FR-11.8 Reports: payroll cost summary, cost by tier/role/team, salary history, raises in period, variable pay — all CSV-exportable with date filters
- ✅ FR-11.9 Simple interface: 7 menu items, plain language, one primary action per screen, wizard with progress for import/raise/tier, preview-before-apply on money changes, 5-card dashboard, smart defaults + helper text + empty states, searchable/sortable/filterable tables with CSV export, undo for imports/reversals, accessible forms
- ✅ FR-11.10 Money as BigDecimal/NUMERIC/encrypted fields; no floating point

## Security controls (BUILD_INSTRUCTIONS §7)

All implemented and covered above/`SECURITY.md` except:
- ⏭ Optional TOTP 2FA (marked (A)) — deferred, see DECISIONS.md
- ⏭ OWASP Dependency-Check/SpotBugs wired into CI — CI itself is not set up in this environment; run locally via the documented commands

## Test results

- Backend: **38/38 green** (`mvn test`) — auth flows, salary-visibility matrix, company isolation, CSV import/undo/export/injection, raise math (percent/fixed/rounding/cap/future-dated), history immutability + reversal, AES-GCM crypto.
- Frontend: **8/8 green** (`npm test`) — money parsing (lakh/crore, decimal comma) and locale formatting; `npm run build` green (17 routes, strict TS).

## Usability self-review (CFO walkthrough, post-Phase-2)

Walked: import employees → create tiers → give a 10% raise to a tier → add a bonus.

- Import: template → fill → upload → confirm = 4 clicks of thinking, errors read like sentences ("Row 12: salary is not a number"), undo is one click. ✅
- Tiers: New tier opens pre-named ("Tier 3"), two money fields, done. Stats show immediately. ✅
- Raise: Give a raise → pick tier → type 10 → Preview shows per-person old/new + total extra per month/year → Confirm. Tier-max conflicts are surfaced with a choice, not a block. ✅
- Bonus: person page → Add component → name/percent/period → Add; total pay updates in place. ✅
- Wording pass: "Give a raise", "Import from a CSV", "Move people to…", "Undo this import" — no "compensation adjustment" anywhere. Salaries hidden by role on the server, not by CSS.

## Open items needing a decision

1. Which countries/regions (labor & privacy rules) — affects retention & consent screens.
2. Payroll export format target (which payroll tool consumes the CSV?).
3. Approval flow: should HR-only companies route raises to a second Admin?
4. Branding/logo.
5. For production: credentials listed in `SECURITY.md`.

# User Guide — for the CFO (and HR)

Everything you need, in the order you'll need it. No jargon.

## First 10 minutes

1. **Set up your company** — open the app, click *Set up your company*, enter the
   company name and your email, choose a password. You are now the Admin.
2. **Check Settings** — pick your currency, number style (1,234,567 vs
   12,34,567), whether salaries are monthly or annual, and how raises round.
3. **Create tiers** — *Tiers → New tier*. Example: Tier 1 from 1,500 to 4,000;
   Tier 2 from 4,000 to 8,000. The range is a guideline — the app warns, never
   blocks.
4. **Bring your people in** — *Employees → Import CSV*:
   1. Download the template.
   2. Fill it (one row per person; only name, start date and salary are required).
   3. Upload it — the app matches the columns for you and shows exactly who will
      be added, updated, or skipped, and any row problems in plain English.
   4. Press **Import**. Made a mistake? **Undo this import** — one click, and
      it's as if it never happened.

## Giving a raise

*Compensation → Give a raise*:

1. Choose **who**: a whole tier, or tick individual people.
2. Choose **how much**: a percent (e.g. 10%) or a fixed amount, and the date it
   takes effect.
3. Press **Preview the cost** — you see every person's old pay, new pay, and the
   total extra cost per month and per year **before anything changes**.
4. If someone would cross their tier maximum, you choose: cap them at the top of
   the tier, or allow it.
5. **Confirm**. Future-dated raises apply automatically on their date. HR can
   prepare a raise for your approval if you prefer a second pair of eyes.

To fix a wrong raise: open the person, find the entry in **Salary history**, and
press **Reverse**. Nothing is ever edited — the correction is added on top, so
the record stays honest.

## Everyday things

- **Add one person**: Employees → Add employee. Only the * fields matter.
- **Someone leaves**: edit their profile → Status → Inactive. Their history stays.
- **Bonuses & commissions**: open the person → Add component (fixed amount, % of
  salary, one-time…). Their total pay updates everywhere instantly.
- **Cost view**: Compensation shows what each tier, role and team costs per month.
- **Reports**: payroll summary by month, cost by tier/role/team, salary history,
  variable pay — all exportable to CSV.
- **Who sees salaries?** Only Admin and CFO/Finance by default. HR can be given
  access in Settings (every view is audit-logged). Managers and employees never
  see anyone else's pay — employees see their own under *My pay*.

## Signing other people in

Admins can create accounts (API or ask your administrator): each person gets a
role — CFO/Finance, HR, Manager, or Employee. Managers see their own team's
profiles but never salaries. Employees see only their own profile and pay.

## If something looks wrong

- **Red text under a field** says exactly what to fix.
- **A warning triangle** next to a tier means someone's salary is outside the
  tier's range — allowed, but worth a look.
- **Anything destructive asks first**, and imports can be undone.

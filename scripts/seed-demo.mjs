#!/usr/bin/env node
/**
 * Demo seed script — NEVER runs automatically. Start the backend first, then:
 *
 *   node scripts/seed-demo.mjs                     # against http://localhost:8080/api
 *   BASE_URL=http://host:8080/api node scripts/seed-demo.mjs
 *
 * Creates a demo company "Acme Trading Ltd" with tiers, roles, 8 employees,
 * variable pay, a raise, and prints the login credentials it generated.
 * Every run creates a FRESH company (emails are randomized), so it can be
 * run repeatedly without clashes.
 */

const BASE = process.env.BASE_URL ?? "http://localhost:8080/api";
const rnd = Math.random().toString(36).slice(2, 7);
const PASSWORD = `Demo-${rnd}-Pass1`;

async function call(token, method, path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : {};
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status}: ${data.error ?? text}`);
  return data;
}

const monthsAgo = (n) => {
  const d = new Date();
  d.setMonth(d.getMonth() - n);
  return d.toISOString().slice(0, 10);
};

(async () => {
  const email = `cfo-${rnd}@acme-demo.test`;
  console.log(`Seeding demo company against ${BASE} …`);

  // 1. company + admin
  const onboarded = await call(null, "POST", "/auth/onboarding", {
    companyName: "Acme Trading Ltd",
    email,
    password: PASSWORD,
  });
  const token = onboarded.accessToken;
  console.log(`✓ company created, admin = ${email} / ${PASSWORD}`);

  // 2. tiers
  const t1 = await call(token, "POST", "/tiers", { name: "Tier 1", description: "Junior / support", minSalary: 1500, maxSalary: 4000, color: "#0f766e" });
  const t2 = await call(token, "POST", "/tiers", { name: "Tier 2", description: "Senior / specialists", minSalary: 4000, maxSalary: 8000, color: "#0369a1" });
  const t3 = await call(token, "POST", "/tiers", { name: "Tier 3", description: "Leadership", minSalary: 8000, maxSalary: 15000, color: "#7c3aed" });
  console.log("✓ tiers: Tier 1, Tier 2, Tier 3");

  // 3. job roles
  await call(token, "POST", "/job-roles", { name: "Software Engineer", defaultTierId: t2.id, minSalary: 4000, maxSalary: 7000 });
  await call(token, "POST", "/job-roles", { name: "Sales Manager", defaultTierId: t2.id, minSalary: 4500, maxSalary: 7500 });
  await call(token, "POST", "/job-roles", { name: "Accountant", defaultTierId: t1.id, minSalary: 3000, maxSalary: 4500 });
  await call(token, "POST", "/job-roles", { name: "Intern", defaultTierId: t1.id, minSalary: 1500, maxSalary: 2000 });
  console.log("✓ job roles");

  // 4. employees (manager first)
  const asha = await call(token, "POST", "/employees", { firstName: "Asha", lastName: "Verma", email: `asha.${rnd}@acme-demo.test`, employeeCode: "E-001", jobRole: null, team: "Sales", startDate: monthsAgo(20), salary: 5200, tierId: t2.id, salaryReason: "Initial salary" });
  // fetch role ids by recreating them? simpler: use names via import-compatible fields is not exposed; set job roles through ids returned above — we didn't capture them; do a lookup by listing:
  const roles = await call(token, "GET", "/job-roles");
  const roleId = (name) => roles.find((r) => r.name === name)?.id ?? null;

  const people = [
    ["Ben", "Okafor", "Engineering", 5600, t2.id, asha.data ? undefined : null],
    ["Chen", "Wei", "Engineering", 3800, t1.id],
    ["Dana", "Petrov", "Finance", 3600, t1.id],
    ["Elif", "Yilmaz", "Sales", 1800, t1.id],
    ["Farid", "Haddad", "Engineering", 9200, t3.id],
  ];
  const ids = [asha.id];
  for (const [first, last, team, salary, tier] of people) {
    const e = await call(token, "POST", "/employees", {
      firstName: first, lastName: last,
      email: `${first.toLowerCase()}.${rnd}@acme-demo.test`,
      employeeCode: `E-0${ids.length + 1}`,
      jobRoleId: roleId(first === "Farid" ? "Software Engineer" : first === "Dana" ? "Accountant" : first === "Elif" ? "Intern" : "Software Engineer"),
      team, startDate: monthsAgo(8 + ids.length), salary, tierId: tier,
      managerId: first === "Farid" ? null : asha.id,
      salaryReason: "Initial salary",
    });
    ids.push(e.id);
  }
  console.log(`✓ ${ids.length} employees`);

  // 5. variable pay for a few people
  await call(token, "POST", `/employees/${ids[1]}/components`, { name: "Annual bonus", calcType: "PERCENT", value: 10, period: "YEARLY" });
  await call(token, "POST", `/employees/${ids[1]}/components`, { name: "Commission", calcType: "PERCENT", value: 4, period: "MONTHLY" });
  await call(token, "POST", `/employees/${ids[0]}/components`, { name: "Quarterly incentive", calcType: "FIXED", value: 900, period: "QUARTERLY" });
  await call(token, "POST", `/employees/${ids[5]}/components`, { name: "Sign-on bonus", calcType: "FIXED", value: 3000, period: "ONE_TIME" });
  console.log("✓ variable pay components");

  // 6. a raise for Tier 1 (applies immediately)
  await call(token, "POST", "/raises", {
    tierId: t1.id, percent: 5, effectiveDate: monthsAgo(2), reason: "Market adjustment",
    capPolicy: "CAP",
  });
  console.log("✓ 5% raise applied to Tier 1 (2 months ago, for history)");

  // 7. an HR user + a manager login
  await call(token, "POST", "/users", { email: `hr-${rnd}@acme-demo.test`, password: PASSWORD, role: "HR" });
  await call(token, "POST", "/users", { email: `mgr-${rnd}@acme-demo.test`, password: PASSWORD, role: "MANAGER", employeeId: asha.id });
  console.log("✓ HR and Manager sign-ins created");

  console.log("\n──────────────── DEMO LOGIN ────────────────");
  console.log(`  URL:      http://localhost:3000  (frontend)`);
  console.log(`  Email:    ${email}`);
  console.log(`  Password: ${PASSWORD}`);
  console.log("─────────────────────────────────────────────\n");
})().catch((e) => {
  console.error("Seed failed:", e.message);
  process.exit(1);
});

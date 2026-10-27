ALTER TABLE users ADD COLUMN password_change_required boolean NOT NULL DEFAULT false;

-- ponytail: shared storage for small HR records, typed and validated by HrCatalog.
-- Sensitive payloads are AES-GCM encrypted; scope and dates remain indexed.
CREATE TABLE hr_records (
  id bigserial PRIMARY KEY,
  company_id bigint NOT NULL REFERENCES companies(id),
  kind varchar(40) NOT NULL,
  employee_id bigint REFERENCES employees(id),
  owner_id bigint NOT NULL REFERENCES users(id),
  payload bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX hr_records_scope ON hr_records(company_id, kind, employee_id);
CREATE TABLE attendance_entries (
  id bigserial PRIMARY KEY,
  company_id bigint NOT NULL REFERENCES companies(id),
  employee_id bigint NOT NULL REFERENCES employees(id),
  task_id bigint REFERENCES hr_records(id),
  clock_in timestamptz NOT NULL,
  clock_out timestamptz,
  CHECK(clock_out IS NULL OR clock_out >= clock_in)
);
CREATE UNIQUE INDEX attendance_one_open ON attendance_entries(employee_id) WHERE clock_out IS NULL;
CREATE INDEX attendance_scope ON attendance_entries(company_id, employee_id, clock_in);
CREATE TABLE timesheets (
  id bigserial PRIMARY KEY,
  company_id bigint NOT NULL REFERENCES companies(id),
  employee_id bigint NOT NULL REFERENCES employees(id),
  week_start date NOT NULL,
  status varchar(20) NOT NULL CHECK(status IN ('SUBMITTED','APPROVED','REJECTED')),
  submitted_at timestamptz NOT NULL DEFAULT now(),
  reviewed_by bigint REFERENCES users(id),
  review_note varchar(1000),
  UNIQUE(company_id, employee_id, week_start)
);

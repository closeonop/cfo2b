-- V1: initial schema — Foundation + CFO core
-- Money columns that hold individual compensation are encrypted in the
-- application (AES-256-GCM) and stored as bytea. Tier/role salary ranges are
-- configuration (not individual data) and stay numeric for reporting.

CREATE TABLE companies (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name             VARCHAR(200) NOT NULL,
  currency         VARCHAR(3)   NOT NULL DEFAULT 'USD',
  locale           VARCHAR(10)  NOT NULL DEFAULT 'en-US',
  salary_period    VARCHAR(10)  NOT NULL DEFAULT 'MONTHLY',  -- MONTHLY | ANNUAL
  rounding_rule    VARCHAR(10)  NOT NULL DEFAULT 'NONE',     -- NONE | NEAREST_1 | NEAREST_10 | NEAREST_100
  hr_can_see_salary BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE tiers (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id  BIGINT NOT NULL REFERENCES companies(id),
  name        VARCHAR(120) NOT NULL,
  description VARCHAR(500),
  min_salary  NUMERIC(14,2),
  max_salary  NUMERIC(14,2),
  color       VARCHAR(20),
  sort_order  INT NOT NULL DEFAULT 0,
  archived    BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (company_id, name)
);

CREATE TABLE job_roles (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id         BIGINT NOT NULL REFERENCES companies(id),
  name               VARCHAR(120) NOT NULL,
  default_tier_id    BIGINT REFERENCES tiers(id),
  min_salary         NUMERIC(14,2),
  max_salary         NUMERIC(14,2),
  default_components JSONB,
  archived           BOOLEAN NOT NULL DEFAULT FALSE,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (company_id, name)
);

CREATE TABLE employees (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id    BIGINT NOT NULL REFERENCES companies(id),
  employee_code VARCHAR(40),
  first_name    VARCHAR(100) NOT NULL,
  last_name     VARCHAR(100) NOT NULL,
  email         VARCHAR(255),
  phone         VARCHAR(40),
  job_role_id   BIGINT REFERENCES job_roles(id),
  tier_id       BIGINT REFERENCES tiers(id),
  manager_id    BIGINT REFERENCES employees(id),
  team          VARCHAR(120),
  start_date    DATE NOT NULL,
  status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | INACTIVE
  base_salary   BYTEA  NOT NULL,                       -- AES-256-GCM encrypted
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_employees_company ON employees(company_id);
CREATE INDEX idx_employees_tier ON employees(tier_id);
CREATE INDEX idx_employees_manager ON employees(manager_id);

CREATE TABLE users (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id      BIGINT NOT NULL REFERENCES companies(id),
  email           VARCHAR(255) NOT NULL UNIQUE,
  password_hash   VARCHAR(100) NOT NULL,
  access_role     VARCHAR(20) NOT NULL, -- ADMIN | CFO | HR | MANAGER | EMPLOYEE
  employee_id     BIGINT REFERENCES employees(id),
  status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash VARCHAR(128) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE password_reset_tokens (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash VARCHAR(128) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  used_at    TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE salary_history (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id        BIGINT NOT NULL REFERENCES companies(id),
  employee_id       BIGINT NOT NULL REFERENCES employees(id),
  effective_date    DATE NOT NULL,
  old_salary        BYTEA NOT NULL,   -- encrypted
  new_salary        BYTEA NOT NULL,   -- encrypted
  percent           NUMERIC(7,4),
  reason            VARCHAR(500),
  changed_by_user_id BIGINT REFERENCES users(id),
  reversal_of_id    BIGINT REFERENCES salary_history(id),
  raise_id          BIGINT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_salary_history_employee ON salary_history(employee_id);

CREATE TABLE raises (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id         BIGINT NOT NULL REFERENCES companies(id),
  status             VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING|APPROVED|REJECTED|APPLIED|CANCELLED
  percent            NUMERIC(7,4),
  fixed_amount       BYTEA,           -- encrypted, nullable
  effective_date     DATE NOT NULL,
  reason             VARCHAR(500) NOT NULL,
  scope_note         VARCHAR(500),
  created_by_user_id BIGINT NOT NULL REFERENCES users(id),
  approved_by_user_id BIGINT REFERENCES users(id),
  approved_at        TIMESTAMPTZ,
  applied_at         TIMESTAMPTZ,
  capped_count       INT NOT NULL DEFAULT 0,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE raise_items (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  raise_id    BIGINT NOT NULL REFERENCES raises(id) ON DELETE CASCADE,
  employee_id BIGINT NOT NULL REFERENCES employees(id),
  old_salary  BYTEA NOT NULL,   -- encrypted
  new_salary  BYTEA NOT NULL,   -- encrypted
  capped      BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_raise_items_raise ON raise_items(raise_id);

CREATE TABLE variable_components (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id  BIGINT NOT NULL REFERENCES companies(id),
  employee_id BIGINT NOT NULL REFERENCES employees(id),
  name        VARCHAR(120) NOT NULL,
  calc_type   VARCHAR(20) NOT NULL, -- FIXED | PERCENT | TARGET
  value       BYTEA NOT NULL,       -- encrypted (amount, percent, or target amount)
  period      VARCHAR(20) NOT NULL, -- MONTHLY | QUARTERLY | YEARLY | ONE_TIME
  start_date  DATE,
  end_date    DATE,
  active      BOOLEAN NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_variable_components_employee ON variable_components(employee_id);

CREATE TABLE import_batches (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id       BIGINT NOT NULL REFERENCES companies(id),
  file_name        VARCHAR(255),
  created_count    INT NOT NULL DEFAULT 0,
  updated_count    INT NOT NULL DEFAULT 0,
  skipped_count    INT NOT NULL DEFAULT 0,
  error_count      INT NOT NULL DEFAULT 0,
  undo_data        JSONB,
  undone_at        TIMESTAMPTZ,
  created_by_user_id BIGINT NOT NULL REFERENCES users(id),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id    BIGINT NOT NULL,
  actor_user_id BIGINT,
  actor_email   VARCHAR(255),
  action        VARCHAR(60) NOT NULL,
  entity_type   VARCHAR(60),
  entity_id     VARCHAR(60),
  detail        JSONB,
  ip_address    VARCHAR(64),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_company_time ON audit_log(company_id, created_at DESC);

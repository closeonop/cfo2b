ALTER TABLE users ADD COLUMN welcome_required boolean NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN email_verified_at timestamptz;
ALTER TABLE users ADD COLUMN otp_requested_at timestamptz;
ALTER TABLE users ADD COLUMN otp_expires_at timestamptz;
ALTER TABLE users ADD COLUMN otp_attempts integer NOT NULL DEFAULT 0;

CREATE TABLE employee_private_profiles (
    employee_id bigint PRIMARY KEY REFERENCES employees(id),
    company_id bigint NOT NULL REFERENCES companies(id),
    payload bytea NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE attendance_entries ADD COLUMN work_summary varchar(1000) NOT NULL DEFAULT '';
ALTER TABLE attendance_entries ADD COLUMN outcome varchar(20) NOT NULL DEFAULT 'IN_PROGRESS';
ALTER TABLE attendance_entries ADD CONSTRAINT attendance_outcome CHECK (outcome IN ('IN_PROGRESS','COMPLETED','BLOCKED'));

CREATE TABLE hr_notifications (
  user_id bigint PRIMARY KEY REFERENCES users(id),
  company_id bigint NOT NULL REFERENCES companies(id),
  payload bytea NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE users ADD COLUMN tours_completed jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE users ADD CONSTRAINT user_tours_array CHECK (
    jsonb_typeof(tours_completed) = 'array' AND jsonb_array_length(tours_completed) <= 128
);

-- ADR 105: a free plan never ends, and team plans have no people cap.
ALTER TABLE subscription ALTER COLUMN expires_at DROP NOT NULL;
ALTER TABLE subscription ALTER COLUMN resource_limit DROP NOT NULL;

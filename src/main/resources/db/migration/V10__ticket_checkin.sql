-- Check-in (JIKU-22). A ticket records when it was checked in and which validator
-- (link label) performed it. Both NULL until the first successful check-in; the
-- ISSUED -> CHECKED_IN transition is an atomic conditional UPDATE in the repository.
ALTER TABLE ticket ADD COLUMN checked_in_at TIMESTAMPTZ;
ALTER TABLE ticket ADD COLUMN checked_in_by VARCHAR(255);

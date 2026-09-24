-- Short shareable code for a counter-staff link (JIKU-88), alongside the
-- signed JWT already issued at creation. Nullable: links created before this
-- migration have none, matching their existing "shown once, never retrievable
-- again" behavior; every link created from here on gets one and can be
-- re-shared or re-copied any time, since the code itself carries no secret —
-- it is only ever resolved into a fresh signed token.
ALTER TABLE service_staff ADD COLUMN code VARCHAR(10);
CREATE UNIQUE INDEX service_staff_code_key ON service_staff (code) WHERE code IS NOT NULL;

-- ADR 105: the client an event is run for. Each part left empty falls back to
-- the organization's own branding.
ALTER TABLE event ADD COLUMN brand_name VARCHAR(255);
ALTER TABLE event ADD COLUMN brand_logo_url VARCHAR(2048);
ALTER TABLE event ADD COLUMN brand_color VARCHAR(7);

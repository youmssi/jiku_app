-- White-label branding on the tenant (JIKU-11). All optional; unset values fall
-- back to Jikū's neutral defaults at read time.
ALTER TABLE tenant ADD COLUMN branding_display_name VARCHAR(255);
ALTER TABLE tenant ADD COLUMN branding_logo_url     VARCHAR(2048);
ALTER TABLE tenant ADD COLUMN branding_primary_color VARCHAR(7);

-- White-label banner image on the tenant, shown above the logo on the public
-- booking page. Optional, like every other branding field; unset falls back to
-- the generated color gradient (see PublicOrgController).
ALTER TABLE tenant ADD COLUMN branding_banner_url VARCHAR(2048);

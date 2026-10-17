-- JIKU-248: a short code per guest for the personal invitation link
-- (/v/K7M2QX9PAB instead of a 400-character signed token). Drawn on first
-- send; the signed tokens already sent keep working. Unique across tenants
-- because a visitor opening the link has no tenant yet.
ALTER TABLE guest ADD COLUMN invite_code VARCHAR(16);
CREATE UNIQUE INDEX uq_guest_invite_code ON guest (invite_code) WHERE invite_code IS NOT NULL;

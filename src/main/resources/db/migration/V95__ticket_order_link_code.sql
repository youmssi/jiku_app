-- JIKU-250: a short code per ticket order for the buyer's link
-- (/c/K7M2QX9PAB instead of a 300-character signed token). Drawn when the
-- order is placed; the signed links already handed out keep working. Unique
-- across tenants because a buyer opening the link has no tenant yet.
ALTER TABLE ticket_order ADD COLUMN link_code VARCHAR(16);
CREATE UNIQUE INDEX uq_ticket_order_link_code ON ticket_order (link_code) WHERE link_code IS NOT NULL;

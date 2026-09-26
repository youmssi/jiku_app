-- How an organization's clients pay it (JIKU-109, ADR 104 §4): the Mobile Money
-- numbers shown to the client, and the organization's own payment link. Jikū
-- only displays them; the money never goes through the platform.
ALTER TABLE tenant ADD COLUMN payment_payee_name VARCHAR(120);
ALTER TABLE tenant ADD COLUMN payment_orange_money VARCHAR(32);
ALTER TABLE tenant ADD COLUMN payment_mtn_momo VARCHAR(32);
ALTER TABLE tenant ADD COLUMN payment_wave VARCHAR(32);
ALTER TABLE tenant ADD COLUMN payment_link_url VARCHAR(500);

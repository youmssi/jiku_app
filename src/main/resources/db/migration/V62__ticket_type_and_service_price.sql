-- What a client pays the organization (JIKU-108, ADR 104 §3). A free ticket
-- type or service has no price: amount and currency are both null. The currency
-- is always the organization's.
ALTER TABLE ticket_type ADD COLUMN price_minor BIGINT;
ALTER TABLE ticket_type ADD COLUMN price_currency VARCHAR(3);
ALTER TABLE ticket_type ADD CONSTRAINT ck_ticket_type_price CHECK (
    (price_minor IS NULL AND price_currency IS NULL) OR (price_minor > 0 AND price_currency IS NOT NULL)
);

ALTER TABLE service ADD COLUMN payment_rule VARCHAR(16) NOT NULL DEFAULT 'FREE';
ALTER TABLE service ALTER COLUMN payment_rule DROP DEFAULT;
ALTER TABLE service ADD COLUMN price_minor BIGINT;
ALTER TABLE service ADD COLUMN price_currency VARCHAR(3);
ALTER TABLE service ADD CONSTRAINT ck_service_price CHECK (
    (payment_rule = 'FREE' AND price_minor IS NULL AND price_currency IS NULL)
    OR (payment_rule <> 'FREE' AND price_minor > 0 AND price_currency IS NOT NULL)
);

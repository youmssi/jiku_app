-- What a ticket's holder owes the organization, fixed when the ticket is issued
-- (JIKU-110, ADR 104 §3). The money goes straight to the organization; the
-- ticket only records whether it is due and who confirmed it was paid.
ALTER TABLE ticket ADD COLUMN payment_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED';
ALTER TABLE ticket ALTER COLUMN payment_status DROP DEFAULT;
ALTER TABLE ticket ADD COLUMN amount_due_minor BIGINT;
ALTER TABLE ticket ADD COLUMN amount_due_currency VARCHAR(3);
ALTER TABLE ticket ADD COLUMN paid_at TIMESTAMPTZ;
ALTER TABLE ticket ADD COLUMN paid_by VARCHAR(255);
ALTER TABLE ticket ADD COLUMN paid_with VARCHAR(16);
ALTER TABLE ticket ADD CONSTRAINT ck_ticket_payment CHECK (
    (payment_status = 'NOT_REQUIRED' AND amount_due_minor IS NULL AND amount_due_currency IS NULL)
    OR (payment_status <> 'NOT_REQUIRED' AND amount_due_minor > 0 AND amount_due_currency IS NOT NULL)
);

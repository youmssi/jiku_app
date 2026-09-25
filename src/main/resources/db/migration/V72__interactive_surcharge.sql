-- ADR 105: the interactive WhatsApp surcharge. usage_record remembers how many
-- of an event's paid guests are covered for the interactive mode, and payment
-- whether it paid for it, so switching mode after paying cannot skip it.
ALTER TABLE usage_record ADD COLUMN interactive_allowance BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment ADD COLUMN interactive BOOLEAN NOT NULL DEFAULT FALSE;

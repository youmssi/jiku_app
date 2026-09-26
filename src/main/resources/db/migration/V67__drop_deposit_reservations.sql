-- Deposit reservations (JIKU-55/57/75) are retired (JIKU-115, JIKU-122): an
-- event's tier is paid in one go when it is activated. Their tables, and the
-- prepaid credit a verified deposit left on an event, go with the feature.
-- Credit notes already issued for refunds stay in the invoice table.
--
-- The migration refuses to run while anything is still owed to a customer, so no
-- open reservation or unspent credit can disappear silently.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM booking WHERE status IN ('DRAFT', 'AWAITING_DEPOSIT', 'DEPOSIT_PAID')) THEN
        RAISE EXCEPTION 'Open deposit reservations remain: close them before removing the feature';
    END IF;
    IF EXISTS (SELECT 1 FROM usage_record WHERE prepaid_amount_minor > 0) THEN
        RAISE EXCEPTION 'Unspent deposit credit remains on events: settle it before removing the feature';
    END IF;
END $$;

DROP TABLE booking_refund;
DROP TABLE booking_payment_declaration;
DROP TABLE booking;

ALTER TABLE usage_record DROP COLUMN prepaid_amount_minor;

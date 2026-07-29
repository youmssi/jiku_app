-- Tracks amount already paid toward an event outside the normal payment flow
-- (JIKU-57): a booking deposit/balance, netted off the price of the event's
-- next manual payment request instead of being charged twice.
ALTER TABLE usage_record ADD COLUMN prepaid_amount_minor BIGINT NOT NULL DEFAULT 0;

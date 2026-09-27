-- JIKU-187: an organizer on the free tier chooses whether the people who
-- answered an open invitation are told by WhatsApp when the event is
-- cancelled; paid tiers always tell them.
ALTER TABLE open_invitation ADD COLUMN notify_on_cancel BOOLEAN NOT NULL DEFAULT FALSE;

-- Ticket transfer (JIKU-64). A confirmed guest may hand their place to someone
-- else while the event allows it. The original guest keeps their row (status
-- TRANSFERRED, ticket cancelled) and the recipient gets a new row holding a
-- freshly issued ticket, so the door always retains the audit trail of who the
-- place originally belonged to and the old QR code stops validating.
ALTER TABLE guest ADD COLUMN transferred_to_guest_id UUID;
ALTER TABLE guest ADD COLUMN transferred_from_guest_id UUID;
ALTER TABLE guest ADD COLUMN transferred_at TIMESTAMPTZ;

CREATE INDEX idx_guest_transferred_from ON guest (transferred_from_guest_id);

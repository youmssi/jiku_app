-- ADR 105: how an event's guests receive their ticket.
ALTER TABLE event ADD COLUMN delivery_mode VARCHAR(24) NOT NULL DEFAULT 'LINK';

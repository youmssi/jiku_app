-- RSVP + capacity (JIKU-19). Events gain an optional capacity and an atomically
-- maintained confirmed count; guests gain an RSVP status.
ALTER TABLE event ADD COLUMN max_capacity INTEGER;
ALTER TABLE event ADD COLUMN confirmed_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE guest ADD COLUMN rsvp_status VARCHAR(32) NOT NULL DEFAULT 'PENDING';

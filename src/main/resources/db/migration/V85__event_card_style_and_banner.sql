-- JIKU-194: the look of an event's guest-facing surfaces, and its banner photo.
ALTER TABLE event ADD COLUMN card_style VARCHAR(16) NOT NULL DEFAULT 'MODERN';
ALTER TABLE event ADD COLUMN banner_updated_at TIMESTAMPTZ;

-- The photo itself, resized by the browser before upload (about 300 KB). Kept
-- next to the event rather than in the private document store so a public page
-- can show it without a signed link.
CREATE TABLE event_banner (
    event_id     UUID PRIMARY KEY REFERENCES event (id) ON DELETE CASCADE,
    tenant_id    VARCHAR(255) NOT NULL,
    content      BYTEA        NOT NULL,
    content_type VARCHAR(32)  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_event_banner_tenant ON event_banner (tenant_id);

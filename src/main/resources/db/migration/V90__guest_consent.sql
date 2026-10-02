-- JIKU-213: the organizer's statement that the guests it imported agreed to
-- hear from it. WhatsApp invitations from the Jikū number only go to guests
-- covered by one; Meta requires that agreement before a business writes first.
ALTER TABLE guest ADD COLUMN consent_attested_at TIMESTAMPTZ;

-- Guests imported before the statement existed were imported for a pilot run
-- with organizers the team knows: they count as covered.
UPDATE guest SET consent_attested_at = created_at;

-- Proof of each statement: who made it, when, for which event and how many guests.
CREATE TABLE guest_consent_attestation (
    id          UUID         PRIMARY KEY,
    tenant_id   VARCHAR(255) NOT NULL,
    event_id    UUID         NOT NULL,
    attested_by VARCHAR(255),
    source      VARCHAR(16)  NOT NULL,
    guest_count INTEGER      NOT NULL,
    attested_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_guest_consent_attestation_event ON guest_consent_attestation (tenant_id, event_id);

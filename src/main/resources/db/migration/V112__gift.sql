-- Gifts the back office offers an organization (JIKU-315): guests for an event,
-- months of a services plan, or months of the organizer pack. A gift waits to
-- be activated by the organizer until claim_by; activating applies it the way a
-- confirmed payment would, without a payment or an invoice.
CREATE TABLE gift (
    id                  UUID PRIMARY KEY,
    tenant_id           VARCHAR(255) NOT NULL,
    kind                VARCHAR(32)  NOT NULL,
    guests              BIGINT,
    days                INTEGER,
    plan                VARCHAR(32),
    months              INTEGER,
    event_id            UUID,
    message             VARCHAR(500),
    claim_by            TIMESTAMPTZ  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    activated_at        TIMESTAMPTZ,
    activated_event_id  UUID,
    trial_grant_id      UUID,
    revoked_reason      VARCHAR(500),
    claim_notice_sent   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by          VARCHAR(255),
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_gift_tenant_created ON gift (tenant_id, created_at DESC);
CREATE INDEX idx_gift_offered_claim_by ON gift (claim_by) WHERE status = 'OFFERED';

-- Trials already running become gifts of guests that were activated when they
-- were granted, so the desk and the organizer see them in one place. Their
-- end date stays the trial's.
INSERT INTO gift (id, tenant_id, kind, guests, days, event_id, claim_by, status,
                  activated_at, activated_event_id, trial_grant_id, created_at, updated_at)
SELECT gen_random_uuid(), t.tenant_id, 'EVENT_GUESTS', t.granted_allowance,
       GREATEST(1, CEIL(EXTRACT(EPOCH FROM (t.expires_at - t.created_at)) / 86400.0))::INTEGER,
       t.event_id, t.created_at, 'ACTIVATED', t.created_at, t.event_id, t.id, t.created_at, now()
FROM trial_grant t
WHERE t.status = 'ACTIVE';

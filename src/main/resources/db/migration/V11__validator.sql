-- Validator access links (JIKU-23). A labeled, revocable, event-scoped link that
-- grants check-in capability without a full account. The link is a signed token
-- (verified statelessly); this row backs immediate revocation and attribution by
-- label. Tenant-scoped.
CREATE TABLE validator (
    id         UUID         PRIMARY KEY,
    tenant_id  VARCHAR(255) NOT NULL,
    event_id   UUID         NOT NULL,
    label      VARCHAR(255) NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_validator_tenant_id ON validator (tenant_id);
CREATE INDEX idx_validator_event_id ON validator (event_id);

-- What the organization was told, kept for the bell in the organizer space
-- (JIKU-314). The text is rendered by the frontend from the kind and its
-- parameters, so a notice reads in whatever language the organizer uses. No
-- guest data is ever copied here.
CREATE TABLE organizer_notice (
    id         UUID PRIMARY KEY,
    tenant_id  VARCHAR(255) NOT NULL,
    kind       VARCHAR(64)  NOT NULL,
    params     TEXT         NOT NULL,
    link       VARCHAR(255),
    created_at TIMESTAMPTZ  NOT NULL,
    read_at    TIMESTAMPTZ
);

CREATE INDEX idx_organizer_notice_tenant_created ON organizer_notice (tenant_id, created_at DESC);
CREATE INDEX idx_organizer_notice_unread ON organizer_notice (tenant_id) WHERE read_at IS NULL;
CREATE INDEX idx_organizer_notice_created ON organizer_notice (created_at);

-- Notification audit log (JIKU-28). One row per delivery attempt, recording the
-- channel, recipient, outcome and time, queryable for support and debugging.
-- Tenant-scoped.
CREATE TABLE notification_log (
    id           UUID         PRIMARY KEY,
    tenant_id    VARCHAR(255) NOT NULL,
    reference_id UUID,
    channel      VARCHAR(32)  NOT NULL,
    recipient    VARCHAR(320) NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    attempt      INTEGER      NOT NULL,
    error        VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_notification_log_tenant_id ON notification_log (tenant_id);
CREATE INDEX idx_notification_log_reference_id ON notification_log (reference_id);

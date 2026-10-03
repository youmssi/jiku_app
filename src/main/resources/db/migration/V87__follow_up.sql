-- JIKU-202: a follow-up the platform team marked as done, so the organization
-- leaves the list for a while instead of being called twice.
CREATE TABLE follow_up_done (
    id        UUID         PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    reason    VARCHAR(32)  NOT NULL,
    done_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_follow_up_done_tenant ON follow_up_done (tenant_id, reason, done_at);

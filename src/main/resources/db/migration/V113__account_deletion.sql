-- Account deletion requests (JIKU-316). The account is erased at scheduled_for
-- unless its holder signs in again first. tenant_ids lists the organizations
-- deleted with it (those it owned alone), comma-separated. email and language
-- are kept only until the final email is sent.
CREATE TABLE account_deletion (
    id            UUID PRIMARY KEY,
    user_id       UUID         NOT NULL,
    email         VARCHAR(320),
    language      VARCHAR(8)   NOT NULL,
    tenant_ids    TEXT         NOT NULL DEFAULT '',
    status        VARCHAR(16)  NOT NULL,
    requested_at  TIMESTAMPTZ  NOT NULL,
    scheduled_for TIMESTAMPTZ  NOT NULL,
    finished_at   TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_account_deletion_pending ON account_deletion (user_id) WHERE status = 'PENDING';
CREATE INDEX idx_account_deletion_due ON account_deletion (scheduled_for) WHERE status = 'PENDING';

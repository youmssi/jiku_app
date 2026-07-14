-- Organization member invitations (JIKU-50): an OWNER/ADMIN invites an email
-- address with a role; accepting creates the membership. Like account_token,
-- only the SHA-256 hash of the invitation token is stored. Rows are kept after
-- acceptance/revocation as the record of who invited whom.
CREATE TABLE member_invitation (
    id         UUID         PRIMARY KEY,
    tenant_id  VARCHAR(255) NOT NULL,
    email      VARCHAR(320) NOT NULL,
    role       VARCHAR(64)  NOT NULL,
    token_hash VARCHAR(64)  NOT NULL,
    status     VARCHAR(32)  NOT NULL,
    invited_by UUID         NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_member_invitation_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_member_invitation_tenant_id ON member_invitation (tenant_id);

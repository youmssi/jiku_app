-- Organizer identity (JIKU-8). This table is deliberately not tenant-filtered:
-- a login looks a user up by email across all tenants before any tenant context
-- exists, so identity is a cross-tenant concern. tenant_id records which tenant
-- the organizer owns.
CREATE TABLE organizer_user (
    id            UUID         PRIMARY KEY,
    tenant_id     VARCHAR(255) NOT NULL,
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_organizer_user_email UNIQUE (email)
);

CREATE INDEX idx_organizer_user_tenant_id ON organizer_user (tenant_id);

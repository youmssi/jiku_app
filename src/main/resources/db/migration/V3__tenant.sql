-- Tenant definition (JIKU-10): the organizer's own space. Not tenant-scoped data,
-- so no tenant_id column. contact_email is unique to reject duplicate sign-ups.
CREATE TABLE tenant (
    id            UUID         PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    contact_email VARCHAR(320) NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_tenant_contact_email UNIQUE (contact_email)
);

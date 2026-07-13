-- Organization memberships (JIKU-48): identity is decoupled from tenancy. A user
-- account stands alone; this table links users to the organizations they belong
-- to, each with a role. Existing organizers become OWNER of the tenant their
-- account was welded to.
CREATE TABLE organizer_membership (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES organizer_user (id) ON DELETE CASCADE,
    tenant_id  VARCHAR(255) NOT NULL,
    role       VARCHAR(64)  NOT NULL,
    invited_by UUID,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_organizer_membership_user_tenant UNIQUE (user_id, tenant_id)
);

CREATE INDEX idx_organizer_membership_tenant_id ON organizer_membership (tenant_id);

INSERT INTO organizer_membership (id, user_id, tenant_id, role, created_at)
SELECT gen_random_uuid(), id, tenant_id, 'OWNER', created_at
FROM organizer_user;

-- The legacy columns stay (nullable) until the frontend migration completes, so a
-- rollback of the application code still finds them; a later migration drops them.
ALTER TABLE organizer_user ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE organizer_user ALTER COLUMN role DROP NOT NULL;

-- One person can now own several organizations, so the contact email is no
-- longer a natural unique key for tenants.
ALTER TABLE tenant DROP CONSTRAINT uq_tenant_contact_email;

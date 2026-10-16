-- JIKU-259: the organization's logo and banner, uploaded instead of linked.
-- Kept in the database next to the tenant, like an event's banner, so public
-- pages and emails can show them without a signed link.
CREATE TABLE tenant_image (
    id           UUID PRIMARY KEY,
    tenant_id    VARCHAR(255) NOT NULL,
    kind         VARCHAR(16)  NOT NULL,
    content      BYTEA        NOT NULL,
    content_type VARCHAR(32)  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_tenant_image_kind UNIQUE (tenant_id, kind)
);

-- Enterprise agreements (JIKU-43). Platform-level metadata about a tenant, not
-- tenant-scoped. Renewal closes a row as RENEWED and opens a new one, so past
-- periods are preserved as rows.
CREATE TABLE agreement (
    id                 UUID         PRIMARY KEY,
    tenant_id          UUID         NOT NULL,
    kind               VARCHAR(32)  NOT NULL,
    period_start       TIMESTAMPTZ  NOT NULL,
    period_end         TIMESTAMPTZ  NOT NULL,
    renewal_at         TIMESTAMPTZ  NOT NULL,
    amount_minor       BIGINT,
    currency           VARCHAR(3),
    status             VARCHAR(32)  NOT NULL,
    notes              TEXT,
    interrupted_reason TEXT,
    renewed_by         UUID,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_agreement_tenant ON agreement (tenant_id);
-- The sweep scans active agreements by period end.
CREATE INDEX idx_agreement_sweep ON agreement (status, period_end);

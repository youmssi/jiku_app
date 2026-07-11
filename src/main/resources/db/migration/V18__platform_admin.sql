-- Platform administration (JIKU-40). Platform admins stand outside every tenant,
-- so neither table is tenant-filtered. The audit log is append-only by
-- application convention: no update or delete path exists.
CREATE TABLE platform_admin (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_platform_admin_email UNIQUE (email)
);

CREATE TABLE admin_audit_log (
    id         UUID         PRIMARY KEY,
    admin_id   UUID         NOT NULL,
    action     VARCHAR(64)  NOT NULL,
    target     VARCHAR(255) NOT NULL,
    note       TEXT,
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_admin_audit_log_action ON admin_audit_log (action);
CREATE INDEX idx_admin_audit_log_created_at ON admin_audit_log (created_at DESC);

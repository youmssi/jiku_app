-- Vérification des organisations (JIKU-175, référentiel métier §9) : personnelle
-- (pièce d'identité + téléphone confirmé) ou entreprise (RCCM, NIF). Obligatoire
-- dès qu'un paiement intervient, facultative sinon. Les documents vivent dans un
-- stockage privé ; seules leurs clés sont ici, et elles sont effacées avec eux.

CREATE TABLE organization_verification (
    id                  UUID         PRIMARY KEY,
    tenant_id           VARCHAR(255) NOT NULL,
    kind                VARCHAR(16)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    legal_name          VARCHAR(200) NOT NULL,
    document_type       VARCHAR(40)  NOT NULL,
    registration_number VARCHAR(80),
    tax_identifier      VARCHAR(80),
    phone               VARCHAR(32),
    submitted_at        TIMESTAMPTZ  NOT NULL,
    decided_at          TIMESTAMPTZ,
    decided_by          UUID,
    rejection_reason    VARCHAR(500),
    documents_purged_at TIMESTAMPTZ,
    CONSTRAINT chk_verification_kind CHECK (kind IN ('PERSONAL', 'COMPANY')),
    CONSTRAINT chk_verification_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE INDEX idx_verification_tenant ON organization_verification (tenant_id);
CREATE INDEX idx_verification_status ON organization_verification (status, submitted_at);

CREATE TABLE verification_document (
    id              UUID         PRIMARY KEY,
    tenant_id       VARCHAR(255) NOT NULL,
    verification_id UUID         NOT NULL,
    position        INT          NOT NULL,
    object_key      VARCHAR(300) NOT NULL,
    content_type    VARCHAR(80)  NOT NULL,
    size_bytes      INT          NOT NULL,
    CONSTRAINT fk_document_verification FOREIGN KEY (verification_id)
        REFERENCES organization_verification (id) ON DELETE CASCADE
);

CREATE INDEX idx_document_verification ON verification_document (verification_id);

CREATE TABLE phone_verification (
    id            UUID         PRIMARY KEY,
    tenant_id     VARCHAR(255) NOT NULL UNIQUE,
    phone         VARCHAR(32)  NOT NULL,
    code_hash     VARCHAR(128) NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    sent_count    INT          NOT NULL DEFAULT 0,
    window_start  TIMESTAMPTZ  NOT NULL,
    verified_at   TIMESTAMPTZ
);

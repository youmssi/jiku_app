-- Commission des billets vendus (JIKU-178, ADR 104 §8, ADR 105 décision 4) :
-- 3 % du prix de chaque billet, payés à Jikū par tranche avant la vente. Une
-- tranche couvre un nombre de billets d'une catégorie ; chaque billet payé en
-- consomme une place. Première tranche offerte, une tranche à crédit, jamais de
-- pause le jour de l'événement (les billets au-delà sont dus après), et la part
-- payée non consommée devient un avoir pendant 12 mois.

CREATE TABLE commission_batch (
    id                    UUID         PRIMARY KEY,
    tenant_id             VARCHAR(255) NOT NULL,
    event_id              UUID         NOT NULL,
    ticket_type_id        UUID         NOT NULL,
    funding               VARCHAR(16)  NOT NULL,
    status                VARCHAR(24)  NOT NULL,
    size                  INT          NOT NULL,
    consumed              INT          NOT NULL DEFAULT 0,
    unit_commission_minor BIGINT       NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    amount_minor          BIGINT       NOT NULL,
    owed_minor            BIGINT       NOT NULL DEFAULT 0,
    credit_applied_minor  BIGINT       NOT NULL DEFAULT 0,
    payment_id            UUID,
    settled_at            TIMESTAMPTZ,
    closes_at             TIMESTAMPTZ  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    closed_at             TIMESTAMPTZ,
    CONSTRAINT chk_commission_batch_funding CHECK (funding IN ('FREE', 'PAID', 'CREDIT', 'OVERAGE')),
    CONSTRAINT chk_commission_batch_status CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'CLOSED', 'CANCELLED')),
    CONSTRAINT chk_commission_batch_consumed CHECK (consumed >= 0)
);

CREATE INDEX idx_commission_batch_category ON commission_batch (tenant_id, event_id, ticket_type_id, status);
CREATE INDEX idx_commission_batch_closing ON commission_batch (closes_at) WHERE status IN ('ACTIVE', 'PENDING_PAYMENT');

CREATE TABLE commission_credit (
    id               UUID         PRIMARY KEY,
    tenant_id        VARCHAR(255) NOT NULL,
    source_batch_id  UUID         NOT NULL,
    amount_minor     BIGINT       NOT NULL,
    remaining_minor  BIGINT       NOT NULL,
    currency         VARCHAR(3)   NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_commission_credit_remaining CHECK (remaining_minor >= 0 AND remaining_minor <= amount_minor)
);

CREATE INDEX idx_commission_credit_tenant ON commission_credit (tenant_id, expires_at);

ALTER TABLE payment ADD COLUMN commission_batch_id UUID;

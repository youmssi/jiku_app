-- Vente publique de billets (JIKU-177, plan de production 6.1) : une commande
-- réserve des places par catégorie, atomiquement, le temps que le client paie
-- l'organisation. Le client déclare son paiement (référence Mobile Money),
-- l'organisation le confirme ; alors seulement les billets sont émis.

-- Durée pendant laquelle une commande non payée garde ses places, choisie par
-- l'organisation ; nulle = la durée par défaut de la plateforme.
ALTER TABLE tenant ADD COLUMN order_hold_minutes INT;

CREATE TABLE ticket_order (
    id                UUID         PRIMARY KEY,
    tenant_id         VARCHAR(255) NOT NULL,
    event_id          UUID         NOT NULL,
    reference         VARCHAR(12)  NOT NULL,
    buyer_name        VARCHAR(120) NOT NULL,
    buyer_phone       VARCHAR(32)  NOT NULL,
    buyer_email       VARCHAR(254),
    status            VARCHAR(24)  NOT NULL,
    total_minor       BIGINT       NOT NULL,
    currency          VARCHAR(3)   NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    expires_at        TIMESTAMPTZ  NOT NULL,
    declared_at       TIMESTAMPTZ,
    payment_reference VARCHAR(80),
    decided_at        TIMESTAMPTZ,
    decided_by        VARCHAR(255),
    rejection_reason  VARCHAR(300),
    CONSTRAINT uq_ticket_order_reference UNIQUE (tenant_id, reference),
    CONSTRAINT chk_ticket_order_status
        CHECK (status IN ('AWAITING_PAYMENT', 'DECLARED', 'PAID', 'EXPIRED', 'REJECTED'))
);

CREATE INDEX idx_ticket_order_event ON ticket_order (tenant_id, event_id, status, created_at);
-- Le balayage des commandes expirées ne lit que celles qui attendent encore un paiement.
CREATE INDEX idx_ticket_order_expiry ON ticket_order (expires_at) WHERE status = 'AWAITING_PAYMENT';

CREATE TABLE ticket_order_line (
    id               UUID         PRIMARY KEY,
    tenant_id        VARCHAR(255) NOT NULL,
    order_id         UUID         NOT NULL REFERENCES ticket_order (id) ON DELETE CASCADE,
    ticket_type_id   UUID         NOT NULL,
    quantity         INT          NOT NULL,
    unit_price_minor BIGINT       NOT NULL,
    CONSTRAINT chk_ticket_order_line_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_ticket_order_line_order ON ticket_order_line (order_id);

-- Un invité acheteur n'a pas été invité : il ne compte ni dans l'import ni dans
-- le palier de l'événement, dont les billets vendus paient la commission (§4).
ALTER TABLE guest ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'INVITED';
ALTER TABLE guest ADD COLUMN order_id UUID;
ALTER TABLE guest ADD CONSTRAINT chk_guest_origin CHECK (origin IN ('INVITED', 'PURCHASED'));
CREATE INDEX idx_guest_order ON guest (order_id) WHERE order_id IS NOT NULL;

-- Abonnement prépayé par ressource active (JIKU-90).
--
-- Une ligne par tenant. Le statut suit la vie d'un abonnement : ACTIVE (valide,
-- possiblement en prépaiement), GRACE (dépassé mais toléré, suspendable à la fin
-- de la grâce) puis EXPIRED (tenant suspendu par le kill-switch). resources_active
-- est une photo du nombre de ressources actives du tenant, maintenue par les
-- événements du module catalog (ResourceCountChanged).
CREATE TABLE subscription (
    id                 UUID         PRIMARY KEY,
    tenant_id          VARCHAR(255) NOT NULL,
    plan               VARCHAR(32)  NOT NULL,
    resource_limit     BIGINT       NOT NULL,
    resources_active   BIGINT       NOT NULL DEFAULT 0,
    started_at         TIMESTAMPTZ  NOT NULL,
    expires_at         TIMESTAMPTZ  NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    expiry_notice_sent BOOLEAN      NOT NULL DEFAULT FALSE,
    grace_notice_sent  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_subscription_tenant UNIQUE (tenant_id)
);

CREATE INDEX idx_subscription_sweep ON subscription (status, expires_at);

-- Les paiements de la table payment portent désormais aussi les renouvellements
-- d'abonnement : kind distingue l'activation de formule d'événement (TIER,
-- défaut, comportement inchangé) du prépaiement d'abonnement (SUBSCRIPTION), pour
-- lequel il n'y a pas d'événement (event_id nul) et dont la durée est portée par
-- subscription_months. Le bureau admin et la confirmation manuelle restent les
-- mêmes pour les deux sortes.
ALTER TABLE payment ALTER COLUMN event_id DROP NOT NULL;
ALTER TABLE payment ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'TIER';
ALTER TABLE payment ADD COLUMN subscription_months INTEGER;

CREATE INDEX idx_payment_kind_provider_status ON payment (kind, provider, status);

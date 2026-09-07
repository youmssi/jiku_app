-- Vocabulaire et gabarits surchargeables par tenant (JIKU-91).
--
-- Un tenant peut renommer les termes produit (ticket, personne…) et remplacer le
-- contenu des gabarits e-mail/WhatsApp adressés à ses clients. En l'absence de
-- ligne, le défaut du produit (ressources du build) s'applique : chaque nouveau
-- métier s'ouvre par configuration, sans modification de code.

-- Gabarit client du tenant. name/channel identifient le gabarit (ex. name
-- « invitation », channel « WHATSAPP ») ; body est le contenu complet du message
-- avec les variables {{…}} du gabarit. active=false suspend l'usage (repli défaut).
CREATE TABLE tenant_template (
    id         UUID         PRIMARY KEY,
    tenant_id  VARCHAR(255) NOT NULL,
    name       VARCHAR(64)  NOT NULL,
    channel    VARCHAR(16)  NOT NULL,
    body       TEXT         NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_tenant_template_key UNIQUE (tenant_id, name, channel)
);

CREATE INDEX idx_tenant_template_tenant_id ON tenant_template (tenant_id);

-- Terme produit surchargé par le tenant, ex. key « appointment » → « consultation ».
CREATE TABLE tenant_vocabulary (
    id         UUID         PRIMARY KEY,
    tenant_id  VARCHAR(255) NOT NULL,
    key        VARCHAR(64)  NOT NULL,
    value      VARCHAR(120) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_tenant_vocabulary_key UNIQUE (tenant_id, key)
);

CREATE INDEX idx_tenant_vocabulary_tenant_id ON tenant_vocabulary (tenant_id);

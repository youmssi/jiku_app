-- Invitation ouverte (JIKU-184, ADR 106) : une invitation sans liste, partagée
-- dans des groupes. Chaque personne répond Je viens / Peut-être / Non ; un
-- « Je viens » prend ses places sous la jauge et reçoit son billet.

CREATE TABLE open_invitation (
    id              UUID         PRIMARY KEY,
    tenant_id       VARCHAR(255) NOT NULL,
    event_id        UUID         NOT NULL,
    -- Code court public : il retrouve l'organisation et l'événement sans compte.
    code            VARCHAR(12)  NOT NULL,
    enabled         BOOLEAN      NOT NULL,
    welcome_message VARCHAR(500),
    max_companions  INT          NOT NULL,
    closes_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_open_invitation_event UNIQUE (tenant_id, event_id),
    CONSTRAINT uq_open_invitation_code UNIQUE (code),
    CONSTRAINT chk_open_invitation_companions CHECK (max_companions >= 0)
);

CREATE TABLE open_response (
    id              UUID         PRIMARY KEY,
    tenant_id       VARCHAR(255) NOT NULL,
    event_id        UUID         NOT NULL,
    -- Chiffres seulement, comme WhatsApp rapporte un expéditeur : une réponse par numéro.
    phone           VARCHAR(20)  NOT NULL,
    name            VARCHAR(120) NOT NULL,
    answer          VARCHAR(8)   NOT NULL,
    companions      INT          NOT NULL,
    -- Le plus grand nombre de personnes jamais compté dans le palier pour cette
    -- réponse : un budget engagé ne se rend pas, il n'est donc jamais recompté.
    committed_heads INT          NOT NULL,
    guest_id        UUID,
    channel         VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    -- Retirée par l'organisation : le numéro ne peut plus répondre.
    removed_at      TIMESTAMPTZ,
    CONSTRAINT uq_open_response_phone UNIQUE (tenant_id, event_id, phone),
    CONSTRAINT chk_open_response_answer CHECK (answer IN ('YES', 'MAYBE', 'NO')),
    CONSTRAINT chk_open_response_channel CHECK (channel IN ('WEB', 'WHATSAPP')),
    CONSTRAINT chk_open_response_companions CHECK (companions >= 0 AND committed_heads >= 0)
);

CREATE INDEX idx_open_response_event ON open_response (tenant_id, event_id, updated_at);
CREATE INDEX idx_open_response_guest ON open_response (guest_id) WHERE guest_id IS NOT NULL;

-- Un invité venu d'une invitation ouverte, et les personnes qu'il amène avec lui.
ALTER TABLE guest DROP CONSTRAINT chk_guest_origin;
ALTER TABLE guest ADD CONSTRAINT chk_guest_origin CHECK (origin IN ('INVITED', 'PURCHASED', 'OPEN'));
ALTER TABLE guest ADD COLUMN companions INT NOT NULL DEFAULT 0;

-- JIKU-282 : d'où viennent les réponses d'une carte. Chaque lien partagé porte
-- son réseau ; on compte les partages et les visites par réseau, et chaque
-- réponse garde le réseau de sa première visite. Des nombres seulement : ni
-- numéro, ni adresse, ni cookie.
ALTER TABLE open_response ADD COLUMN source VARCHAR(16);

CREATE TABLE open_card_funnel (
    id         UUID         PRIMARY KEY,
    tenant_id  VARCHAR(255) NOT NULL,
    event_id   UUID         NOT NULL,
    source     VARCHAR(16)  NOT NULL,
    shares     BIGINT       NOT NULL DEFAULT 0,
    visits     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_open_card_funnel UNIQUE (tenant_id, event_id, source),
    CONSTRAINT chk_open_card_funnel_counts CHECK (shares >= 0 AND visits >= 0)
);

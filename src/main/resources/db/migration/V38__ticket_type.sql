-- Catégories d'accès d'un événement (JIKU-93).
--
-- Un gala d'entreprise à Conakry mélange 300 invités en salle, 40 en carré VIP
-- avec accès au cocktail, et 12 personnes du protocole avec accès scène.
-- Aujourd'hui l'organisateur doit créer trois événements séparés, ce qui casse
-- le compteur de présence global et oblige le portier à jongler entre trois
-- liens de validation.
--
-- Purement additif : un événement sans catégorie déclarée se comporte
-- exactement comme avant, et aucune donnée existante n'est migrée.
CREATE TABLE ticket_type (
    id           UUID         PRIMARY KEY,
    tenant_id    VARCHAR(255) NOT NULL,
    event_id     UUID         NOT NULL,
    label        VARCHAR(120) NOT NULL,
    -- Null = pas de plafond propre ; seule la capacité globale de l'événement
    -- s'applique alors.
    max_capacity INTEGER,
    -- Compteur de la catégorie, incrémenté par la même transition atomique
    -- conditionnelle que la capacité globale (voir TicketTypeRepository).
    confirmed_count INTEGER   NOT NULL DEFAULT 0,
    -- Lisible à deux mètres sur l'écran du portier, dans une lumière difficile.
    color_hex    VARCHAR(7)   NOT NULL,
    position     INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT fk_ticket_type_event FOREIGN KEY (event_id) REFERENCES event (id),
    CONSTRAINT uq_ticket_type_label UNIQUE (event_id, label)
);

CREATE INDEX idx_ticket_type_event_id ON ticket_type (event_id);
CREATE INDEX idx_ticket_type_tenant_id ON ticket_type (tenant_id);

-- Rattachement de l'invité, puis recopie sur le ticket à l'émission : le billet
-- porte sa catégorie même si l'invité change ensuite, comme le reste du ticket
-- est figé à l'émission.
ALTER TABLE guest ADD COLUMN ticket_type_id UUID;
ALTER TABLE ticket ADD COLUMN ticket_type_id UUID;

CREATE INDEX idx_guest_ticket_type_id ON guest (ticket_type_id);

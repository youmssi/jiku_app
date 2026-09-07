-- Ticket porteur d'un créneau et d'un type (JIKU-83).
--
-- Un rendez-vous, un sans-rendez-vous et une invitation partagent le même objet
-- Ticket ; ce qui les distingue est la présence d'un créneau. Purement additif :
-- guestId reste non nul, uq_ticket_guest n'est pas touchée, et les tickets
-- existants prennent kind = 'INVITATION' avec un créneau nul. Réversible : ces
-- colonnes et cette table ne portent aucune donnée existante, leur abandon
-- ultérieur ne perd rien.
--
-- RENDEZ_VOUS et SANS_RENDEZ_VOUS sont nommés APPOINTMENT et WALK_IN dans le code
-- (l'enum TicketKind) ; seuls des tickets d'invitation existent aujourd'hui, donc
-- aucune valeur réelle ne dépend de ce choix tant que l'offre rendez-vous n'émet pas.
ALTER TABLE ticket ADD COLUMN kind       VARCHAR(32)  NOT NULL DEFAULT 'INVITATION';
ALTER TABLE ticket ADD COLUMN starts_at  TIMESTAMPTZ;
ALTER TABLE ticket ADD COLUMN ends_at    TIMESTAMPTZ;
-- Heure de présence effective (arrivée au comptoir / à la porte) pour un créneau.
ALTER TABLE ticket ADD COLUMN arrived_at TIMESTAMPTZ;
-- Rang du jour, séquentiel par service et par jour (ligne du jour). La ressource
-- "service" n'existe pas encore (JIKU-84) : la colonne est posée ici, l'unicité et
-- le compteur atomique arrivent avec la ligne du jour (JIKU-88).
ALTER TABLE ticket ADD COLUMN day_rank   INTEGER;

CREATE INDEX idx_ticket_kind ON ticket (kind);
CREATE INDEX idx_ticket_starts_at ON ticket (starts_at);

-- Table de liaison ticket → ressource, créée dès le premier jour (JIKU-83 §2.2) :
-- le schéma coûte cher à changer, le moteur non. La table resource et sa clé
-- étrangère arrivent en JIKU-84 ; resource_id reste donc sans contrainte ici.
CREATE TABLE ticket_resource (
    id          UUID         PRIMARY KEY,
    tenant_id   VARCHAR(255) NOT NULL,
    ticket_id   UUID         NOT NULL,
    resource_id UUID,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_ticket_resource_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id) ON DELETE CASCADE
);

CREATE INDEX idx_ticket_resource_tenant_id ON ticket_resource (tenant_id);
CREATE INDEX idx_ticket_resource_ticket_id ON ticket_resource (ticket_id);

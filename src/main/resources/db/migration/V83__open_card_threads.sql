-- Conversation WhatsApp d'une invitation ouverte (JIKU-185) : la dernière
-- invitation dont un numéro a écrit le code, pour comprendre un nombre tapé seul
-- (les accompagnants). Lue avant qu'aucune organisation ne soit connue, donc hors
-- du filtre d'organisation, comme les fils des invitations interactives.
CREATE TABLE open_card_thread (
    phone      VARCHAR(20) PRIMARY KEY,
    code       VARCHAR(12) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

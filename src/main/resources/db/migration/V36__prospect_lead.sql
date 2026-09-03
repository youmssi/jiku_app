-- Capture des professionnels intéressés par la prise de rendez-vous (JIKU-98).
--
-- La campagne d'acquisition tourne avant que le produit rendez-vous n'existe. Sans
-- cette table, chaque visiteur intéressé est perdu et la dépense publicitaire ne
-- produit rien de réutilisable.
--
-- Délibérément PAS tenant-scopé, comme `booking` : au moment où il laisse ses
-- coordonnées, le prospect n'a aucun compte. La donnée est de niveau plateforme et
-- n'est visible que depuis le bureau d'administration.
--
-- Aucune donnée sensible : nom d'activité, contact et secteur. Rien qui relève de
-- la santé, même si une clinique s'inscrit — le champ `sector` est déclaratif.
CREATE TABLE prospect_lead (
    id             UUID         PRIMARY KEY,
    business_name  VARCHAR(255) NOT NULL,
    contact_name   VARCHAR(255) NOT NULL,
    phone          VARCHAR(32)  NOT NULL,
    email          VARCHAR(255),
    sector         VARCHAR(40)  NOT NULL,
    city           VARCHAR(120),
    -- Volume déclaré de rendez-vous par semaine : sert à trier qui rappeler d'abord.
    weekly_volume  VARCHAR(20),
    note           VARCHAR(1000),
    -- Provenance issue du `?src=` des liens de campagne, comme pour booking.
    source         VARCHAR(100),
    status         VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    contacted_at   TIMESTAMPTZ,
    -- Un même professionnel qui soumet deux fois ne crée pas deux pistes.
    CONSTRAINT uq_prospect_lead_phone UNIQUE (phone)
);

CREATE INDEX idx_prospect_lead_created_at ON prospect_lead (created_at DESC);
CREATE INDEX idx_prospect_lead_status ON prospect_lead (status);

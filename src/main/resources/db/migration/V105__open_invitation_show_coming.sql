-- JIKU-286 : l'hôte peut afficher « 23 personnes viennent » sur sa carte.
-- Désactivé par défaut (décision D3) ; seul le nombre s'affiche, jamais un nom.
ALTER TABLE open_invitation ADD COLUMN show_coming BOOLEAN NOT NULL DEFAULT FALSE;

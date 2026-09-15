-- Identifiant public de l'organisation (JIKU-XXX) : le profil découvert à
-- https://jiku.app/o/<username>. Facultatif, unique quand renseigné.
ALTER TABLE tenant ADD COLUMN username VARCHAR(32);

CREATE UNIQUE INDEX uq_tenant_username ON tenant (username) WHERE username IS NOT NULL;

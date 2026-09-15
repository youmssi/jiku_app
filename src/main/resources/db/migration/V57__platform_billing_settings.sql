-- Réglages de facturation gérés par le bureau admin (bénéficiaire des virements
-- et grille de prix), persistés en base pour être modifiables sans redéploiement.
-- Une seule ligne (id=1). Les colonnes JSON restent NULL tant que l'opérateur
-- n'a rien personnalisé : les valeurs de configuration (env) restent le défaut.
CREATE TABLE platform_billing_settings (
    id                      BIGINT       PRIMARY KEY,
    payee_name              TEXT,
    payee_contact_email     TEXT,
    payee_contact_phone     TEXT,
    mobile_money_number     TEXT,
    mobile_money_operator   TEXT,
    bank_details            TEXT,
    tier_prices_json        TEXT,
    subscription_plans_json TEXT,
    updated_at              TIMESTAMPTZ  NOT NULL,
    updated_by              VARCHAR(255)
);

INSERT INTO platform_billing_settings (id, updated_at) VALUES (1, now());
